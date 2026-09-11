package com.vertex.service.mm.paper;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.vertex.service.mm.grid.mapper.GridOrderMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Paper 模式下的内存挂单簿。按 symbol → side → price 组织。
 * <p>
 * <b>ID 生成</b>：paperOrderId 需持久唯一（DB 有 exchange_order_id UNIQUE 约束）。
 * 启动时从 DB max(exchange_order_id) seed，避免重启后 id 撞已存在的历史 Paper 记录。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaperOrderBook {

    private static final long MIN_PAPER_ORDER_ID = 1_000_000L;

    private final GridOrderMapper gridOrderMapper;

    private final AtomicLong idGenerator = new AtomicLong(MIN_PAPER_ORDER_ID);

    @PostConstruct
    void seedFromDb() {
        try {
            // 拿 grid_order.exchange_order_id 最大值 —— 支持 PAPER 和 LIVE 混合场景
            QueryWrapper<com.vertex.service.mm.grid.entity.GridOrder> qw = new QueryWrapper<>();
            qw.select("MAX(CAST(exchange_order_id AS UNSIGNED)) AS mx");
            List<Map<String, Object>> rows = gridOrderMapper.selectMaps(qw);
            long dbMax = 0;
            if (rows != null && !rows.isEmpty() && rows.get(0).get("mx") != null) {
                dbMax = ((Number) rows.get(0).get("mx")).longValue();
            }
            long seed = Math.max(MIN_PAPER_ORDER_ID, dbMax) + 1;
            idGenerator.set(seed - 1);   // incrementAndGet 后为 seed
            log.info("[PaperOrderBook] seeded idGenerator from DB max={} → next paperOrderId={}",
                    dbMax, seed);
        } catch (Exception ex) {
            log.warn("[PaperOrderBook] seed from DB failed: {}, using default {}",
                    ex.getMessage(), MIN_PAPER_ORDER_ID);
        }
    }

    /** paperOrderId → order（快速定位）*/
    private final Map<Long, PaperOrder> byId = new ConcurrentHashMap<>();
    /** clientOrderId → order */
    private final Map<String, PaperOrder> byClientId = new ConcurrentHashMap<>();

    /** symbol + BUY  → price 降序 map (best bid first) */
    private final Map<String, NavigableMap<BigDecimal, List<PaperOrder>>> buyBooks = new ConcurrentHashMap<>();
    /** symbol + SELL → price 升序 map (best ask first) */
    private final Map<String, NavigableMap<BigDecimal, List<PaperOrder>>> sellBooks = new ConcurrentHashMap<>();

    public long nextOrderId() { return idGenerator.incrementAndGet(); }

    /** 测试用：手动设 seed（生产不用） */
    void setSeed(long seed) { idGenerator.set(seed); }

    /** 添加一笔挂单 */
    public synchronized void place(PaperOrder order) {
        byId.put(order.getPaperOrderId(), order);
        if (order.getClientOrderId() != null) byClientId.put(order.getClientOrderId(), order);

        var book = "BUY".equalsIgnoreCase(order.getSide())
                ? buyBooks.computeIfAbsent(order.getSymbol(),
                        k -> new ConcurrentSkipListMap<>(Comparator.reverseOrder()))
                : sellBooks.computeIfAbsent(order.getSymbol(),
                        k -> new ConcurrentSkipListMap<>());
        book.computeIfAbsent(order.getPrice(), k -> new ArrayList<>()).add(order);
    }

    /** 撤单 */
    public synchronized boolean cancel(long paperOrderId) {
        PaperOrder o = byId.remove(paperOrderId);
        if (o == null) return false;
        if (o.getClientOrderId() != null) byClientId.remove(o.getClientOrderId());
        o.setStatus("CANCELED");
        removeFromBook(o);
        return true;
    }

    private void removeFromBook(PaperOrder o) {
        var book = "BUY".equalsIgnoreCase(o.getSide()) ? buyBooks.get(o.getSymbol()) : sellBooks.get(o.getSymbol());
        if (book == null) return;
        List<PaperOrder> list = book.get(o.getPrice());
        if (list != null) {
            list.remove(o);
            if (list.isEmpty()) book.remove(o.getPrice());
        }
    }

    /** 拿单（by id or clientOrderId）*/
    public PaperOrder get(long paperOrderId) { return byId.get(paperOrderId); }
    public PaperOrder getByClientId(String clientOrderId) { return byClientId.get(clientOrderId); }

    /**
     * 返回价格"在扫单路径上"的 maker 单（合理撮合模型）。
     * <p>
     * <b>规则</b>：
     * <ul>
     *   <li>taker BUY (makerSide=SELL) 打到 P → 所有 SELL 单价格 &le; P 都被扫（sweep up）</li>
     *   <li>taker SELL (makerSide=BUY) 打到 P → 所有 BUY 单价格 &ge; P 都被扫（sweep down）</li>
     * </ul>
     * <p>
     * 上层 {@link PaperFillSimulator} 用 {@code queueLossFactor × trade.qty} 限制总成交量，
     * 避免一根 trade 虚填多档。
     * </p>
     * <p>
     * 按价格优先 + 时间优先排序：先填最激进的价（对 taker 最优），同价按下单时间 FIFO。
     * </p>
     */
    public synchronized List<PaperOrder> ordersThatWouldFillAt(String symbol, BigDecimal tradePrice, String tradeSideMaker) {
        boolean matchBuy = "BUY".equalsIgnoreCase(tradeSideMaker);
        var book = matchBuy ? buyBooks.get(symbol) : sellBooks.get(symbol);
        if (book == null) return List.of();

        List<PaperOrder> hits = new ArrayList<>();
        // BUY book 按 price 降序 (best bid first)，需要 price ≥ tradePrice
        // SELL book 按 price 升序 (best ask first)，需要 price ≤ tradePrice
        for (var e : book.entrySet()) {
            BigDecimal p = e.getKey();
            boolean inPath = matchBuy
                    ? p.compareTo(tradePrice) >= 0   // BUY 单价 ≥ taker sell 打下的价
                    : p.compareTo(tradePrice) <= 0;  // SELL 单价 ≤ taker buy 打上的价
            if (!inPath) break;   // 有序遍历，遇到第一个 out-of-path 就停
            hits.addAll(e.getValue());
        }
        // 同价按 FIFO；不同价保留 book 顺序（best price 优先）
        hits.sort((a, b) -> {
            int cmp = matchBuy
                    ? b.getPrice().compareTo(a.getPrice())   // BUY: 高价优先
                    : a.getPrice().compareTo(b.getPrice());  // SELL: 低价优先
            if (cmp != 0) return cmp;
            return Long.compare(a.getPlacedTimeMs(), b.getPlacedTimeMs());
        });
        return hits;
    }

    /** 某价位当前排队总量（含所有 maker 单）*/
    public BigDecimal queueQtyAtPrice(String symbol, BigDecimal price, String side) {
        var book = "BUY".equalsIgnoreCase(side) ? buyBooks.get(symbol) : sellBooks.get(symbol);
        if (book == null) return BigDecimal.ZERO;
        List<PaperOrder> list = book.get(price);
        if (list == null) return BigDecimal.ZERO;
        BigDecimal sum = BigDecimal.ZERO;
        for (PaperOrder o : list) {
            BigDecimal remain = o.getQuantity().subtract(o.getFilledQty() == null ? BigDecimal.ZERO : o.getFilledQty());
            if (remain.compareTo(BigDecimal.ZERO) > 0) sum = sum.add(remain);
        }
        return sum;
    }

    /** 清空 —— 策略停止时调 */
    public synchronized void clearForSymbol(String symbol) {
        buyBooks.remove(symbol);
        sellBooks.remove(symbol);
        byId.entrySet().removeIf(e -> symbol.equalsIgnoreCase(e.getValue().getSymbol()));
        byClientId.entrySet().removeIf(e -> symbol.equalsIgnoreCase(e.getValue().getSymbol()));
    }
}

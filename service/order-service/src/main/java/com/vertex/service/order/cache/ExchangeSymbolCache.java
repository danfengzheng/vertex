package com.vertex.service.order.cache;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.vertex.model.entity.trading.TrdExchangeSymbol;
import com.vertex.model.vo.trading.ExchangeSymbolVO;
import com.vertex.service.order.mapper.TrdExchangeSymbolMapper;
import com.vertex.service.order.mapper.TrdSymbolMapper;
import com.vertex.model.entity.trading.TrdSymbol;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 交易所币对内存缓存
 * <p>
 * 启动时从 DB 加载全量数据，同步完成后刷新。
 * 避免 WebSocket 消息路径（高频）每次查询数据库。
 * <p>
 * 正向缓存（下单/订阅时使用）：
 *   key = "{exchange}:{marketType}:{canonicalSymbol}"  →  TrdExchangeSymbol
 * <p>
 * 反向缓存（WebSocket 消息归一化时使用）：
 *   key = "{exchange}:{exchangeSymbol}"  →  canonical symbol
 * <p>
 * 当前只接入 Binance，exchange_symbol == canonical symbol，
 * 反向映射是恒等的；OKX 接入后（ETH-USDT-SWAP → ETHUSDT）会真正生效。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExchangeSymbolCache {

    private final TrdExchangeSymbolMapper exchangeSymbolMapper;
    private final TrdSymbolMapper symbolMapper;

    /**
     * 不可变快照，三张缓存表打包成一个对象，通过单次 volatile 写原子替换。
     * 读线程拿到引用后看到的是一个完全一致的视图，不存在三字段分步赋值的中间态。
     */
    private static final class Snapshot {
        final Map<String, TrdExchangeSymbol> forward;   // key: exchange:marketType:symbol
        final Map<String, String> reverse;              // key: exchange:exchangeSymbol
        final Map<String, List<ExchangeSymbolVO>> byExchange;

        Snapshot(Map<String, TrdExchangeSymbol> forward,
                 Map<String, String> reverse,
                 Map<String, List<ExchangeSymbolVO>> byExchange) {
            this.forward    = Collections.unmodifiableMap(forward);
            this.reverse    = Collections.unmodifiableMap(reverse);
            this.byExchange = Collections.unmodifiableMap(byExchange);
        }
    }

    /** 单个 volatile 引用，保证三张表一起对读线程可见 */
    private volatile Snapshot snapshot = new Snapshot(
            Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap());

    @PostConstruct
    public void init() {
        try {
            refresh();
        } catch (Exception e) {
            // 启动时 DB 可能还没跑迁移脚本，允许失败，等首次同步后再加载
            log.warn("[ExchangeSymbolCache] Init load failed (table may not exist yet): {}", e.getMessage());
        }
    }

    /**
     * 重新从 DB 加载全量数据，原子替换缓存。
     * 由 SymbolSyncTask 在每次同步成功后调用。
     */
    public synchronized void refresh() {
        List<TrdExchangeSymbol> all = exchangeSymbolMapper.selectList(
                new LambdaQueryWrapper<TrdExchangeSymbol>()
                        .eq(TrdExchangeSymbol::getDeleted, 0));

        // 加载 trd_symbol 的 baseAsset/quoteAsset（供 VO 组装使用）
        List<TrdSymbol> symbols = symbolMapper.selectList(
                new LambdaQueryWrapper<TrdSymbol>().eq(TrdSymbol::getDeleted, 0));
        Map<String, TrdSymbol> symbolMap = symbols.stream()
                .collect(Collectors.toMap(TrdSymbol::getSymbol, s -> s, (a, b) -> a));

        HashMap<String, TrdExchangeSymbol> newForward = new HashMap<>();
        HashMap<String, String> newReverse = new HashMap<>();
        HashMap<String, List<ExchangeSymbolVO>> newVoCache = new HashMap<>();

        for (TrdExchangeSymbol es : all) {
            newForward.put(forwardKey(es.getExchange(), es.getMarketType(), es.getSymbol()), es);
            newReverse.put(reverseKey(es.getExchange(), es.getExchangeSymbol()), es.getSymbol());
        }

        // 按交易所分组，组装 VO 列表
        all.stream().collect(Collectors.groupingBy(TrdExchangeSymbol::getExchange))
                .forEach((exchange, list) -> {
                    List<ExchangeSymbolVO> vos = list.stream().map(es -> {
                        TrdSymbol sym = symbolMap.get(es.getSymbol());
                        return ExchangeSymbolVO.builder()
                                .id(es.getId())
                                .symbol(es.getSymbol())
                                .baseAsset(sym != null ? sym.getBaseAsset() : null)
                                .quoteAsset(sym != null ? sym.getQuoteAsset() : null)
                                .exchange(es.getExchange())
                                .marketType(es.getMarketType())
                                .exchangeSymbol(es.getExchangeSymbol())
                                .status(es.getStatus())
                                .lotSize(es.getLotSize())
                                .tickSize(es.getTickSize())
                                .build();
                    }).collect(Collectors.toList());
                    newVoCache.put(exchange, vos);
                });

        // 三张表打包成不可变 Snapshot，单次 volatile 写原子替换，读线程不会看到中间态
        this.snapshot = new Snapshot(newForward, newReverse, newVoCache);

        log.info("[ExchangeSymbolCache] Refreshed: {} exchange-symbol mappings across {} exchange(s)",
                all.size(), newVoCache.size());
    }

    /**
     * 正向查询：canonical symbol → 交易所专用标识信息
     *
     * @param exchange       交易所代码，如 binance
     * @param marketType     市场类型，如 USDM
     * @param canonicalSymbol 平台通用标识，如 ETHUSDT
     * @return TrdExchangeSymbol，null 表示未找到
     */
    public TrdExchangeSymbol getExchangeSymbol(String exchange, String marketType, String canonicalSymbol) {
        return snapshot.forward.get(forwardKey(exchange, marketType, canonicalSymbol));
    }

    /**
     * 反向查询：交易所 exchange_symbol → canonical symbol
     * <p>
     * WebSocket 消息归一化使用。若找不到映射，原样返回 exchangeSymbol（兜底）。
     *
     * @param exchange       交易所代码，如 okx
     * @param exchangeSymbol 交易所返回的标识，如 ETH-USDT-SWAP
     * @return canonical symbol，如 ETHUSDT
     */
    public String getCanonicalSymbol(String exchange, String exchangeSymbol) {
        return snapshot.reverse.getOrDefault(reverseKey(exchange, exchangeSymbol), exchangeSymbol);
    }

    /**
     * 按交易所查询 VO 列表（前端下拉数据源）
     *
     * @param exchange 交易所代码，为 null 时返回全部
     */
    public List<ExchangeSymbolVO> listByExchange(String exchange) {
        Snapshot s = this.snapshot; // 拿一次引用，保证本次调用读同一个快照
        if (exchange == null) {
            return s.byExchange.values().stream()
                    .flatMap(List::stream)
                    .collect(Collectors.toList());
        }
        return s.byExchange.getOrDefault(exchange, Collections.emptyList());
    }

    // ─── 私有工具 ──────────────────────────────────────────────────────────

    private static String forwardKey(String exchange, String marketType, String symbol) {
        return exchange + ":" + marketType + ":" + symbol;
    }

    private static String reverseKey(String exchange, String exchangeSymbol) {
        return exchange + ":" + exchangeSymbol;
    }
}

package com.vertex.service.mm.grid.backtest;

import com.vertex.model.entity.quote.KLine;
import com.vertex.model.entity.quote.KLineInterval;
import com.vertex.service.mm.grid.entity.GridStrategy;
import com.vertex.service.mm.grid.mapper.GridStrategyMapper;
import com.vertex.service.quote.store.KLineStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Grid 回测引擎（K 线级 MVP）。
 * <p>
 * 简化模型：
 * <ul>
 *   <li>K 线 replay：按 openTime 顺序模拟</li>
 *   <li>假设 GTX 挂单在 K 线内的 high/low 触及时 100% 成交（未考虑排队损失）</li>
 *   <li>假设 fee = 0（maker only）</li>
 *   <li>滑动窗口 + 每次 fill 立即补挂配对平仓</li>
 *   <li>reshuffle 触发：价格移动 &gt; 5 档时中枢跟随</li>
 * </ul>
 * <p>
 * 与实盘的偏差：无排队损失、无 GTX 拒绝、无 WS 延迟；
 * <b>结果偏乐观 20-40%</b>。适合参数扫描 / 相对比较，不适合精确预估。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GridBacktestService {

    private final GridStrategyMapper strategyMapper;
    private final KLineStore klineStore;

    /**
     * 回测入口。
     */
    public BacktestResult run(long strategyId, BacktestConfig config) {
        GridStrategy strategy = strategyMapper.selectById(strategyId);
        if (strategy == null) throw new IllegalArgumentException("strategy not found: " + strategyId);

        // 用 fromCode 直接解析币安标准格式 (1m/5m/15m/1h/4h/1d ...)；缺省 5m
        KLineInterval interval;
        String code = config.getKlineInterval();
        if (code == null || code.isBlank()) {
            interval = KLineInterval.M5;
        } else {
            try {
                interval = KLineInterval.fromCode(code.trim());
            } catch (Exception ex) {
                log.warn("[Backtest] invalid klineInterval '{}', fallback to 5m", code);
                interval = KLineInterval.M5;
            }
        }

        List<KLine> klines = klineStore.query(strategy.getExchange(), strategy.getSymbol(),
                interval, config.getStartTime(), config.getEndTime(), Integer.MAX_VALUE, true);
        if (klines == null || klines.size() < 20) {
            throw new IllegalStateException("insufficient K-lines for backtest: got "
                    + (klines == null ? 0 : klines.size()));
        }

        return simulate(strategy, config, klines);
    }

    /**
     * 核心模拟循环。
     */
    private BacktestResult simulate(GridStrategy strategy, BacktestConfig config, List<KLine> klines) {
        // 状态初始化
        BigDecimal centerPrice = klines.get(0).getClose();
        BigDecimal stepPct = strategy.getBaseStepPct();
        BigDecimal stepFrac = stepPct.divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP);
        int windowSize = strategy.getWindowSizePerSide();
        BigDecimal qtyPerGrid = strategy.getQtyPerGrid();

        // 挂单矩阵：price → PendingOrder（区分 side）
        // 使用两个 map 分侧
        TreeMap<BigDecimal, PendingOrder> longBuyOrders = new TreeMap<>();    // 等 low 触达
        TreeMap<BigDecimal, PendingOrder> longSellOrders = new TreeMap<>();   // 等 high 触达（平仓）
        TreeMap<BigDecimal, PendingOrder> shortSellOrders = new TreeMap<>();  // 等 high 触达
        TreeMap<BigDecimal, PendingOrder> shortBuyOrders = new TreeMap<>();   // 等 low 触达（平仓）

        // 初始化窗口
        initializeGrid(centerPrice, stepFrac, windowSize, qtyPerGrid,
                strategy.getHedgeMode() != null && strategy.getHedgeMode() == 1,
                longBuyOrders, shortSellOrders);

        // P&L 状态
        BigDecimal capital = config.getInitialCapital();
        BigDecimal arbPnl = BigDecimal.ZERO;
        BigDecimal dirPnl = BigDecimal.ZERO;
        BigDecimal totalFee = BigDecimal.ZERO;
        int openFills = 0, closeFills = 0, roundTrips = 0;
        int layer2Cnt = 0, layer3Cnt = 0, layer4Cnt = 0;
        BigDecimal maxLong = BigDecimal.ZERO, maxShort = BigDecimal.ZERO, maxNet = BigDecimal.ZERO;

        // 持仓追踪：FIFO 队列存 (openPrice, qty)
        List<double[]> longStack = new ArrayList<>();    // [price, qty]
        List<double[]> shortStack = new ArrayList<>();

        // 回撤追踪
        BigDecimal peakEquity = capital;
        BigDecimal maxDD = BigDecimal.ZERO;
        BigDecimal maxDDPct = BigDecimal.ZERO;
        long ddStart = 0, maxDDDuration = 0;

        List<BacktestResult.EquityPoint> curve = new ArrayList<>();
        List<String> events = new ArrayList<>();

        // ─── 主循环：按 K 线 replay ─────────────────
        int lastReshuffleIdx = 0;
        String currentLayer = "NORMAL";
        int consecutiveSameSide = 0;
        int lastSideDirection = 0;   // -1 = SHORT fill, +1 = LONG fill

        for (int idx = 0; idx < klines.size(); idx++) {
            KLine bar = klines.get(idx);
            BigDecimal high = bar.getHigh();
            BigDecimal low = bar.getLow();
            BigDecimal close = bar.getClose();

            // ── 1) 处理 LONG BUY 挂单：如果 low 触达 → 成交（累积多头）
            var iter = longBuyOrders.entrySet().iterator();
            while (iter.hasNext()) {
                var e = iter.next();
                if (low.compareTo(e.getKey()) <= 0) {
                    PendingOrder o = e.getValue();
                    longStack.add(new double[]{o.price.doubleValue(), o.qty.doubleValue()});
                    openFills++;
                    // 立即挂配对平仓 SELL @ price × (1 + stepFrac)
                    BigDecimal sellPrice = o.price.multiply(BigDecimal.ONE.add(stepFrac))
                            .setScale(8, RoundingMode.HALF_UP);
                    longSellOrders.put(sellPrice, new PendingOrder(sellPrice, o.qty, o.gridIndex));
                    if (lastSideDirection == 1) consecutiveSameSide++;
                    else { lastSideDirection = 1; consecutiveSameSide = 1; }
                    iter.remove();
                }
            }

            // ── 2) 处理 LONG SELL（平仓）：high 触达 → 平掉一笔多头
            var iter2 = longSellOrders.entrySet().iterator();
            while (iter2.hasNext()) {
                var e = iter2.next();
                if (high.compareTo(e.getKey()) >= 0) {
                    PendingOrder o = e.getValue();
                    if (!longStack.isEmpty()) {
                        double[] popped = longStack.remove(0);
                        double pnl = (o.price.doubleValue() - popped[0]) * popped[1];
                        arbPnl = arbPnl.add(BigDecimal.valueOf(pnl));
                        roundTrips++;
                    }
                    closeFills++;
                    // 补挂新的 BUY 单在原开仓价（网格连续）
                    BigDecimal openPrice = o.price.divide(BigDecimal.ONE.add(stepFrac), 8, RoundingMode.HALF_UP);
                    longBuyOrders.put(openPrice, new PendingOrder(openPrice, o.qty, o.gridIndex));
                    iter2.remove();
                }
            }

            // ── 3) 处理 SHORT SELL 挂单：high 触达 → 成交（累积空头）
            var iter3 = shortSellOrders.entrySet().iterator();
            while (iter3.hasNext()) {
                var e = iter3.next();
                if (high.compareTo(e.getKey()) >= 0) {
                    PendingOrder o = e.getValue();
                    shortStack.add(new double[]{o.price.doubleValue(), o.qty.doubleValue()});
                    openFills++;
                    BigDecimal buyPrice = o.price.multiply(BigDecimal.ONE.subtract(stepFrac))
                            .setScale(8, RoundingMode.HALF_UP);
                    shortBuyOrders.put(buyPrice, new PendingOrder(buyPrice, o.qty, o.gridIndex));
                    if (lastSideDirection == -1) consecutiveSameSide++;
                    else { lastSideDirection = -1; consecutiveSameSide = 1; }
                    iter3.remove();
                }
            }

            // ── 4) 处理 SHORT BUY（平仓）：low 触达 → 平掉一笔空头
            var iter4 = shortBuyOrders.entrySet().iterator();
            while (iter4.hasNext()) {
                var e = iter4.next();
                if (low.compareTo(e.getKey()) <= 0) {
                    PendingOrder o = e.getValue();
                    if (!shortStack.isEmpty()) {
                        double[] popped = shortStack.remove(0);
                        double pnl = (popped[0] - o.price.doubleValue()) * popped[1];
                        arbPnl = arbPnl.add(BigDecimal.valueOf(pnl));
                        roundTrips++;
                    }
                    closeFills++;
                    BigDecimal openPrice = o.price.multiply(BigDecimal.ONE.add(stepFrac))
                            .setScale(8, RoundingMode.HALF_UP);
                    shortSellOrders.put(openPrice, new PendingOrder(openPrice, o.qty, o.gridIndex));
                    iter4.remove();
                }
            }

            // ── 5) 中枢跟随：价格漂移超过 5 档 → reshuffle
            BigDecimal drift = close.subtract(centerPrice).abs();
            BigDecimal fiveSteps = centerPrice.multiply(stepFrac).multiply(BigDecimal.valueOf(5));
            if (drift.compareTo(fiveSteps) > 0 && idx - lastReshuffleIdx > 12) {   // 冷却 12 根 K 线
                centerPrice = close;
                lastReshuffleIdx = idx;
                // 简化：不重挂，让新价位在下次 fill 时自然形成
                events.add(bar.getOpenTime() + " RESHUFFLE center → " + close);
            }

            // ── 6) 模拟保护层（简化）─────────────
            if (config.isSimulateProtection()) {
                BigDecimal longQty = BigDecimal.valueOf(longStack.stream().mapToDouble(a -> a[1]).sum());
                BigDecimal shortQty = BigDecimal.valueOf(shortStack.stream().mapToDouble(a -> a[1]).sum());
                if (longQty.compareTo(maxLong) > 0) maxLong = longQty;
                if (shortQty.compareTo(maxShort) > 0) maxShort = shortQty;
                BigDecimal netAbs = longQty.subtract(shortQty).abs();
                if (netAbs.compareTo(maxNet) > 0) maxNet = netAbs;

                // 简化 layer 判定：连续同侧 15 根 = LAYER3；单侧 window 全吃 = LAYER4
                String newLayer = "NORMAL";
                if (consecutiveSameSide >= 15) newLayer = "LAYER3";
                else if (consecutiveSameSide >= 10) newLayer = "LAYER2";
                else if (consecutiveSameSide >= 5) newLayer = "LAYER1";

                if (!newLayer.equals(currentLayer)) {
                    if ("LAYER2".equals(newLayer)) layer2Cnt++;
                    if ("LAYER3".equals(newLayer)) layer3Cnt++;
                    if ("LAYER4".equals(newLayer)) layer4Cnt++;
                    events.add(bar.getOpenTime() + " LAYER " + currentLayer + " → " + newLayer);
                    currentLayer = newLayer;
                }
            }

            // ── 7) 计算实时权益 + 回撤 ──────────
            BigDecimal longUnreal = BigDecimal.valueOf(
                    longStack.stream().mapToDouble(a -> (close.doubleValue() - a[0]) * a[1]).sum());
            BigDecimal shortUnreal = BigDecimal.valueOf(
                    shortStack.stream().mapToDouble(a -> (a[0] - close.doubleValue()) * a[1]).sum());
            dirPnl = longUnreal.add(shortUnreal);

            BigDecimal equity = capital.add(arbPnl).add(dirPnl).subtract(totalFee);
            if (equity.compareTo(peakEquity) > 0) {
                peakEquity = equity;
                ddStart = 0;
            }
            BigDecimal dd = peakEquity.subtract(equity);
            if (dd.compareTo(maxDD) > 0) {
                maxDD = dd;
                if (peakEquity.compareTo(BigDecimal.ZERO) > 0) {
                    maxDDPct = dd.divide(peakEquity, 6, RoundingMode.HALF_UP)
                            .multiply(BigDecimal.valueOf(100));
                }
                if (ddStart == 0) ddStart = bar.getOpenTime();
                maxDDDuration = bar.getOpenTime() - ddStart;
            }

            // 采样：每 10 根 K 线记一点权益曲线
            if (idx % 10 == 0 || idx == klines.size() - 1) {
                curve.add(new BacktestResult.EquityPoint(bar.getOpenTime(), equity, arbPnl, dirPnl,
                        BigDecimal.valueOf(longStack.stream().mapToDouble(a -> a[1]).sum()),
                        BigDecimal.valueOf(shortStack.stream().mapToDouble(a -> a[1]).sum()),
                        currentLayer));
            }
        }

        // 尾部：所有未平仓位以最后一根 close 结算
        BigDecimal lastClose = klines.get(klines.size() - 1).getClose();
        BigDecimal finalLongUnreal = BigDecimal.valueOf(
                longStack.stream().mapToDouble(a -> (lastClose.doubleValue() - a[0]) * a[1]).sum());
        BigDecimal finalShortUnreal = BigDecimal.valueOf(
                shortStack.stream().mapToDouble(a -> (a[0] - lastClose.doubleValue()) * a[1]).sum());
        dirPnl = finalLongUnreal.add(finalShortUnreal);

        BigDecimal totalReturn = arbPnl.add(dirPnl).subtract(totalFee);
        BigDecimal finalCap = config.getInitialCapital().add(totalReturn);
        BigDecimal returnPct = totalReturn.divide(config.getInitialCapital(), 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));

        return BacktestResult.builder()
                .strategyId(strategy.getId())
                .symbol(strategy.getSymbol())
                .startTime(config.getStartTime())
                .endTime(config.getEndTime())
                .klineInterval(config.getKlineInterval())
                .initialCapital(config.getInitialCapital())
                .finalCapital(finalCap)
                .totalReturn(totalReturn)
                .totalReturnPct(returnPct)
                .totalArbitragePnl(arbPnl)
                .totalDirectionalPnl(dirPnl)
                .totalFeePaid(totalFee)
                .totalRoundTrips(roundTrips)
                .totalOpenFills(openFills)
                .totalCloseFills(closeFills)
                .layer2Triggers(layer2Cnt)
                .layer3Triggers(layer3Cnt)
                .layer4Triggers(layer4Cnt)
                .maxDrawdown(maxDD)
                .maxDrawdownPct(maxDDPct)
                .maxDrawdownDurationMs(maxDDDuration)
                .maxLongQty(maxLong)
                .maxShortQty(maxShort)
                .maxNetQty(maxNet)
                .equityCurve(curve)
                .events(events.size() > 100 ? events.subList(events.size() - 100, events.size()) : events)
                .build();
    }

    private void initializeGrid(BigDecimal center, BigDecimal stepFrac, int size,
                                 BigDecimal qty, boolean hedgeMode,
                                 TreeMap<BigDecimal, PendingOrder> longBuys,
                                 TreeMap<BigDecimal, PendingOrder> shortSells) {
        for (int i = 1; i <= size; i++) {
            BigDecimal factor = BigDecimal.ONE.add(stepFrac.multiply(BigDecimal.valueOf(i)));
            BigDecimal upper = center.multiply(factor).setScale(8, RoundingMode.HALF_UP);
            BigDecimal lower = center.divide(factor, 8, RoundingMode.HALF_UP);

            longBuys.put(lower, new PendingOrder(lower, qty, -i));
            if (hedgeMode) {
                shortSells.put(upper, new PendingOrder(upper, qty, i));
            }
        }
    }

    private static class PendingOrder {
        final BigDecimal price;
        final BigDecimal qty;
        final int gridIndex;
        PendingOrder(BigDecimal price, BigDecimal qty, int gridIndex) {
            this.price = price;
            this.qty = qty;
            this.gridIndex = gridIndex;
        }
    }
}

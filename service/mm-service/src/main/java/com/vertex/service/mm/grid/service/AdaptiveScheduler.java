package com.vertex.service.mm.grid.service;

import com.vertex.model.entity.quote.KLine;
import com.vertex.model.entity.quote.KLineInterval;
import com.vertex.model.entity.strategy.IndicatorType;
import com.vertex.service.mm.grid.entity.GridStrategy;
import com.vertex.service.quote.store.KLineStore;
import com.vertex.service.strategy.indicator.IndicatorRegistry;
import com.vertex.service.strategy.indicator.IndicatorResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 自适应步长 + 中枢定时任务。
 * <p>
 * 每 5 分钟对所有 RUNNING 策略触发：
 * <ol>
 *   <li>从 quote-service 拉 K 线</li>
 *   <li>计算 ADX + ATR</li>
 *   <li>调 AdaptiveStepCalculator 得出建议</li>
 *   <li>若需调整 → 调 SlidingWindowManager.shiftWindow / adjustStep</li>
 *   <li>OrderIntentExecutor 落实</li>
 * </ol>
 * </p>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "vertex.mm", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class AdaptiveScheduler {

    private static final long DEFAULT_INTERVAL_MS = 5 * 60_000L;

    private final GridStrategyService strategyService;
    private final SlidingWindowManager slidingWindow;
    private final AdaptiveStepCalculator calculator;
    private final OrderIntentExecutor executor;
    private final AccountCredentialResolver credentialResolver;
    private final KLineStore klineStore;
    private final GridEventLogger eventLogger;
    private final IndicatorRegistry indicatorRegistry;
    private final TrendIndicatorCache trendIndicatorCache;
    private final com.vertex.service.mm.risk.WindowDepletionAnalyzer depletionAnalyzer;

    @Scheduled(fixedDelay = DEFAULT_INTERVAL_MS, initialDelay = 60_000L)
    public void tickAll() {
        List<GridStrategy> running = strategyService.listRunning();
        if (running.isEmpty()) return;
        for (GridStrategy strategy : running) {
            try {
                if (strategy.getAdaptiveStepEnabled() != null && strategy.getAdaptiveStepEnabled() != 1) continue;
                tickOne(strategy);
            } catch (Exception ex) {
                log.error("[AdaptiveScheduler] strategy={} tick error: {}", strategy.getId(), ex.getMessage(), ex);
            }
        }
    }

    private void tickOne(GridStrategy strategy) {
        GridState state = slidingWindow.getState(strategy.getId());
        if (state == null) return;

        // ── Step 0: 补空档 —— GTX 拒单的 slot（orderId=null）重新挂一次
        refillEmptySlots(strategy, state);

        // ── 拉 K 线（主周期用 strategy.trend_layer1_interval，缺省 5m）─────
        KLineInterval interval = resolveInterval(strategy.getTrendLayer1Interval());
        List<KLine> klines = klineStore.query(strategy.getExchange(), strategy.getSymbol(),
                interval, null, null, 100, true);
        if (klines == null || klines.size() < 30) {
            log.debug("[AdaptiveScheduler] strategy={} not enough K-lines yet ({})",
                    strategy.getId(), klines == null ? 0 : klines.size());
            return;
        }

        // ── 计算指标（ADX 走 strategy-service.IndicatorRegistry；ATR/EMA 本地简易实现）─
        BigDecimal atr = computeAtr(klines, 14);
        BigDecimal avgAtr = computeAvgAtr(klines, 14, 60);
        BigDecimal adx = computeAdxViaRegistry(klines, 14);
        BigDecimal ema20Close = computeEmaClose(klines, 20);

        // 缓存 ADX 供 GridEngine 综合评分用
        trendIndicatorCache.putAdx(strategy.getId(), adx);

        // ── 调 AdaptiveStepCalculator ────────────
        StepAdjustment adj = calculator.calculate(strategy, state, adx, atr, avgAtr, ema20Close);
        if (!adj.isShouldAdjust()) return;

        Map<String, Object> payload = new HashMap<>();
        payload.put("newStep", adj.getNewStepPct());
        payload.put("newCenter", adj.getNewCenterPrice());
        payload.put("trendState", adj.getTrendState());
        payload.put("reason", adj.getReason());
        eventLogger.info(strategy.getId(), "STEP_ADJUST", payload);

        // ── 应用调整（只写内存）—— DB sync 由 PnlSnapshotScheduler 每分钟统一批量做 ──
        state.setLastAdaptiveAt(java.time.LocalDateTime.now());
        state.setStepPct(adj.getNewStepPct());

        // 中枢显著漂移 → 触发 shift（内部处理渐进边缘迁移）
        BigDecimal oldCenter = state.getCenterPrice();
        if (oldCenter == null || adj.getNewCenterPrice() == null) return;
        BigDecimal drift = adj.getNewCenterPrice().subtract(oldCenter).abs();
        BigDecimal driftPct = drift.divide(oldCenter, 6, java.math.RoundingMode.HALF_UP);
        if (driftPct.compareTo(new BigDecimal("0.005")) < 0) return;  // < 0.5% 不动 center

        List<GridOrderIntent> intents = slidingWindow.shiftWindow(strategy, adj.getNewCenterPrice());
        if (intents.isEmpty()) return;

        try {
            var creds = credentialResolver.resolveOrPaper(strategy.getAccountId(),
                    "PAPER".equalsIgnoreCase(strategy.getExecutionMode()));
            var summary = executor.execute(intents, creds.apiKey(), creds.apiSecret());
            depletionAnalyzer.onReshuffle(strategy.getId());   // 重置 depletion 基线
            log.info("[AdaptiveScheduler] strategy={} shifted center {} → {}, {}",
                    strategy.getId(), oldCenter, adj.getNewCenterPrice(), summary);
            eventLogger.info(strategy.getId(), "RESHUFFLE", Map.of(
                    "from", oldCenter, "to", adj.getNewCenterPrice(), "summary", summary.toString()));
        } catch (Exception ex) {
            log.error("[AdaptiveScheduler] strategy={} apply shift failed: {}", strategy.getId(), ex.getMessage());
        }
    }

    /**
     * 扫描 slot 里 orderId=null 的空档（GTX 拒单/撤单后未重挂），重新生成 PLACE intent。
     * 5min 一次，避免爆 rate limit。
     */
    private void refillEmptySlots(GridStrategy strategy, GridState state) {
        List<GridOrderIntent> refills = new java.util.ArrayList<>();
        boolean hedgeMode = strategy.getHedgeMode() != null && strategy.getHedgeMode() == 1;
        BigDecimal qty = strategy.getQtyPerGrid();

        // LONG 侧 (lower slots) BUY 单
        if (state.isLongSideEnabled()) {
            for (var e : state.getLowerSlots().entrySet()) {
                var slot = e.getValue();
                if (slot.getLongSideOrderId() != null) continue;
                refills.add(GridOrderIntent.builder()
                        .strategyId(strategy.getId()).symbol(strategy.getSymbol())
                        .side("BUY").positionSide("LONG").role("OPEN")
                        .price(e.getKey()).qty(qty).gridIndex(slot.getGridIndex())
                        .actionType(GridOrderIntent.ActionType.PLACE).build());
            }
        }

        // SHORT 侧 (upper slots) SELL 单
        if (state.isShortSideEnabled() && hedgeMode) {
            for (var e : state.getUpperSlots().entrySet()) {
                var slot = e.getValue();
                if (slot.getShortSideOrderId() != null) continue;
                refills.add(GridOrderIntent.builder()
                        .strategyId(strategy.getId()).symbol(strategy.getSymbol())
                        .side("SELL").positionSide("SHORT").role("OPEN")
                        .price(e.getKey()).qty(qty).gridIndex(slot.getGridIndex())
                        .actionType(GridOrderIntent.ActionType.PLACE).build());
            }
        }

        if (refills.isEmpty()) return;

        try {
            var creds = credentialResolver.resolveOrPaper(strategy.getAccountId(),
                    "PAPER".equalsIgnoreCase(strategy.getExecutionMode()));
            var summary = executor.execute(refills, creds.apiKey(), creds.apiSecret());
            log.info("[AdaptiveScheduler] strategy={} refilled {} empty slots, {}",
                    strategy.getId(), refills.size(), summary);
            if (summary.placeSuccess > 0) {
                eventLogger.info(strategy.getId(), "SLOT_REFILL", Map.of(
                        "attempted", refills.size(),
                        "success", summary.placeSuccess,
                        "gtxRejected", summary.gtxRejected,
                        "failed", summary.placeFailed));
            }
        } catch (Exception ex) {
            log.warn("[AdaptiveScheduler] refill error strategy={}: {}", strategy.getId(), ex.getMessage());
        }
    }

    /** 从 strategy 配置解析主周期 K 线；无效或空回退到 5m */
    private KLineInterval resolveInterval(String code) {
        if (code == null || code.isBlank()) return KLineInterval.M5;
        try {
            KLineInterval i = KLineInterval.fromCode(code.trim());
            return i == null ? KLineInterval.M5 : i;
        } catch (Exception ex) {
            log.warn("[AdaptiveScheduler] invalid interval '{}', fallback to 5m", code);
            return KLineInterval.M5;
        }
    }

    // ─── 指标计算辅助 ───────────

    private BigDecimal computeAtr(List<KLine> klines, int period) {
        if (klines == null || klines.size() < 2) return null;
        double[] tr = new double[klines.size() - 1];
        for (int i = 1; i < klines.size(); i++) {
            double h = klines.get(i).getHigh().doubleValue();
            double l = klines.get(i).getLow().doubleValue();
            double pc = klines.get(i - 1).getClose().doubleValue();
            tr[i - 1] = Math.max(h - l, Math.max(Math.abs(h - pc), Math.abs(l - pc)));
        }
        int calcPeriod = Math.min(period, tr.length);
        double atr = 0;
        for (int i = 0; i < calcPeriod; i++) atr += tr[i];
        atr /= calcPeriod;
        for (int i = calcPeriod; i < tr.length; i++) {
            atr = (atr * (period - 1) + tr[i]) / period;
        }
        return BigDecimal.valueOf(atr);
    }

    /**
     * baseline ATR = 用最后 {@code baseline} 根 K 线的 True Range 简单均值。
     * <p>
     * 之前的实现对每根都重算 ATR (O(N²) 且窗口重叠不一致)。这里改成一次线性扫。
     * </p>
     */
    private BigDecimal computeAvgAtr(List<KLine> klines, int atrPeriod, int baseline) {
        if (klines == null || klines.size() < 2) return null;
        int start = Math.max(1, klines.size() - baseline);
        double sumTr = 0;
        int count = 0;
        for (int i = start; i < klines.size(); i++) {
            double h = klines.get(i).getHigh().doubleValue();
            double l = klines.get(i).getLow().doubleValue();
            double pc = klines.get(i - 1).getClose().doubleValue();
            double tr = Math.max(h - l, Math.max(Math.abs(h - pc), Math.abs(l - pc)));
            sumTr += tr;
            count++;
        }
        if (count == 0) return computeAtr(klines, atrPeriod);
        return BigDecimal.valueOf(sumTr / count);
    }

    /**
     * 复用 strategy-service 已注册的 AdxIndicator（保证一致性 + 不用重写）。
     * 出错返回 null，由上层降级为"跳过 ADX 维度打分"。
     */
    private BigDecimal computeAdxViaRegistry(List<KLine> klines, int period) {
        try {
            if (!indicatorRegistry.supports(IndicatorType.ADX)) return null;
            var indicator = indicatorRegistry.get(IndicatorType.ADX);
            int need = indicator.requiredDataPoints(java.util.Map.of("period", period));
            if (klines.size() < need) return null;
            IndicatorResult r = indicator.calculate(klines, java.util.Map.of("period", period));
            if (r == null || r.getValues() == null) return null;
            Double v = r.getValues().get("adx");
            return v == null ? null : BigDecimal.valueOf(v);
        } catch (Exception ex) {
            log.debug("[AdaptiveScheduler] ADX calc failed: {}", ex.getMessage());
            return null;
        }
    }

    private BigDecimal computeEmaClose(List<KLine> klines, int period) {
        if (klines == null || klines.size() < period) return null;
        double multiplier = 2.0 / (period + 1);
        double ema = klines.subList(0, period).stream()
                .mapToDouble(k -> k.getClose().doubleValue()).average().orElse(0);
        for (int i = period; i < klines.size(); i++) {
            double close = klines.get(i).getClose().doubleValue();
            ema = close * multiplier + ema * (1 - multiplier);
        }
        return BigDecimal.valueOf(ema);
    }
}

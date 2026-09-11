package com.vertex.service.mm.grid.service;

import com.vertex.service.mm.grid.entity.GridStrategy;
import com.vertex.service.mm.market.MarketQuote;
import com.vertex.service.mm.market.MarketQuoteCache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 自适应步长 + 中枢计算器。
 * <p>
 * 输入：ADX / ATR / spread / 当前配置
 * 输出：新步长 + 新中枢 + 建议动作
 * </p>
 * <p>
 * <b>骨架版本</b>：ADX / ATR 由 GridEngine 通过 strategy-service 的 IndicatorRegistry 传入。
 * 本类只做纯计算，不依赖任何指标源。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdaptiveStepCalculator {

    private static final BigDecimal MAX_ADJUSTMENT_RATIO = new BigDecimal("0.5");
    private static final int MIN_MINUTES_BETWEEN_ADJUSTMENTS = 5;

    private final MarketQuoteCache marketQuoteCache;

    /**
     * 计算新步长和中枢建议。
     *
     * @param strategy    策略配置
     * @param state       当前网格状态
     * @param currentAdx  当前 ADX(14)
     * @param currentAtr  当前 ATR(14)
     * @param avgAtr      长期平均 ATR (EMA over baseline_period)
     * @param recentEmaClose 主周期 EMA 平滑的 close（用于中枢跟随）
     * @return 调整建议（可能 shouldAdjust=false）
     */
    public StepAdjustment calculate(GridStrategy strategy, GridState state,
                                     BigDecimal currentAdx, BigDecimal currentAtr,
                                     BigDecimal avgAtr, BigDecimal recentEmaClose) {

        // ── 时间去抖 ─────────────────────────────────
        if (state.getLastAdaptiveAt() != null) {
            long minutesElapsed = Duration.between(state.getLastAdaptiveAt(), LocalDateTime.now()).toMinutes();
            if (minutesElapsed < MIN_MINUTES_BETWEEN_ADJUSTMENTS) {
                return notAdjust(strategy, "adaptive_cooldown");
            }
        }

        BigDecimal baseStep = strategy.getBaseStepPct();
        if (baseStep == null || baseStep.compareTo(BigDecimal.ZERO) <= 0) {
            return notAdjust(strategy, "base_step_not_set");
        }

        // ── 波动率乘数 ───────────────────────────────
        BigDecimal volMult;
        BigDecimal volRatio = null;
        if (currentAtr != null && avgAtr != null && avgAtr.compareTo(BigDecimal.ZERO) > 0) {
            volRatio = currentAtr.divide(avgAtr, 6, RoundingMode.HALF_UP);
            double v = volRatio.doubleValue();
            v = Math.max(0.6, Math.min(3.0, v));
            volMult = BigDecimal.valueOf(v);
        } else {
            volMult = BigDecimal.ONE;
        }

        // ── 趋势乘数（ADX 分段）─────────────────────
        double trendMult = 1.0;
        String trendState = "RANGING";
        String suggestedAction = "NORMAL";
        if (currentAdx != null) {
            double adx = currentAdx.doubleValue();
            double l4 = orDouble(strategy.getTrendLayer4AdxEnter(), 35);
            double l3 = orDouble(strategy.getTrendLayer3AdxEnter(), 30);
            double l2 = orDouble(strategy.getTrendLayer2AdxEnter(), 25);
            if (adx > l4) { trendMult = 2.0; trendState = "STRONG"; suggestedAction = "PAUSE_ADVERSE"; }
            else if (adx > l3) { trendMult = 1.3; trendState = "MODERATE"; suggestedAction = "ASYMMETRIC"; }
            else if (adx > l2) { trendMult = 1.0; trendState = "WEAK"; suggestedAction = "ASYMMETRIC"; }
            else if (adx > 20) { trendMult = 1.0; trendState = "WEAK"; }
            else { trendMult = 0.8; trendState = "RANGING"; }
        }

        BigDecimal newStep = baseStep.multiply(volMult).multiply(BigDecimal.valueOf(trendMult))
                .setScale(4, RoundingMode.HALF_UP);

        // ── 最小步长保护（防 GTX 频繁拒绝）──────────
        BigDecimal minimumSafeStep = computeMinimumSafeStep(strategy);
        if (newStep.compareTo(minimumSafeStep) < 0) {
            newStep = minimumSafeStep;
        }

        // ── 单次调整幅度限制（±50%）─────────────────
        BigDecimal currentStep = state.getStepPct() != null ? state.getStepPct() : baseStep;
        BigDecimal maxChange = currentStep.multiply(MAX_ADJUSTMENT_RATIO);
        BigDecimal minAllowed = currentStep.subtract(maxChange);
        BigDecimal maxAllowed = currentStep.add(maxChange);
        if (newStep.compareTo(minAllowed) < 0) newStep = minAllowed;
        if (newStep.compareTo(maxAllowed) > 0) newStep = maxAllowed;

        // ── 中枢跟随（EMA 平滑）──────────────────────
        BigDecimal newCenter = recentEmaClose != null ? recentEmaClose : state.getCenterPrice();

        // ── 判断是否需要真正调整 ────────────────────
        boolean stepChanged = state.getStepPct() == null
                || newStep.subtract(state.getStepPct()).abs()
                        .compareTo(state.getStepPct().multiply(new BigDecimal("0.05"))) > 0;
        boolean centerChanged = state.getCenterPrice() == null
                || newCenter.subtract(state.getCenterPrice()).abs()
                        .compareTo(state.getCenterPrice().multiply(new BigDecimal("0.005"))) > 0;

        if (!stepChanged && !centerChanged) {
            return notAdjust(strategy, "changes_below_threshold");
        }

        return StepAdjustment.builder()
                .strategyId(strategy.getId())
                .shouldAdjust(true)
                .newStepPct(newStep)
                .newCenterPrice(newCenter)
                .trendState(trendState)
                .suggestedAction(suggestedAction)
                .reason(String.format("adx=%s vol_ratio=%s", currentAdx, volRatio))
                .currentAdx(currentAdx)
                .currentVolRatio(volRatio)
                .minimumSafeStep(minimumSafeStep)
                .build();
    }

    /**
     * 最小安全步长：spread × safety_multiplier（防 GTX 拒绝）。
     */
    public BigDecimal computeMinimumSafeStep(GridStrategy strategy) {
        MarketQuote quote = marketQuoteCache.get(strategy.getSymbol());
        BigDecimal defaultMin = new BigDecimal("0.10");   // 兜底 0.1%
        if (quote == null) return defaultMin;
        BigDecimal spreadPct = quote.spreadPct();
        if (spreadPct == null || spreadPct.compareTo(BigDecimal.ZERO) <= 0) return defaultMin;
        BigDecimal mult = strategy.getMinimumStepSafetyMultiplier() == null
                ? new BigDecimal("5.0") : strategy.getMinimumStepSafetyMultiplier();
        return spreadPct.multiply(mult).setScale(4, RoundingMode.HALF_UP);
    }

    private StepAdjustment notAdjust(GridStrategy strategy, String reason) {
        return StepAdjustment.builder()
                .strategyId(strategy.getId())
                .shouldAdjust(false)
                .reason(reason)
                .build();
    }

    private static double orDouble(BigDecimal v, double fallback) {
        return v == null ? fallback : v.doubleValue();
    }
}

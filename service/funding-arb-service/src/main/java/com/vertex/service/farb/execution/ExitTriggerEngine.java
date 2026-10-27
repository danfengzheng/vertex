package com.vertex.service.farb.execution;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.vertex.service.farb.entity.FarbPosition;
import com.vertex.service.farb.entity.FarbStrategy;
import com.vertex.service.farb.mapper.FarbPositionMapper;
import com.vertex.service.farb.mapper.FarbStrategyMapper;
import com.vertex.service.farb.regime.PredictionState;
import com.vertex.service.farb.regime.RegimeMetrics;
import com.vertex.service.farb.regime.RegimeMetricsCalculator;
import com.vertex.service.farb.regime.ReversalPredictor;
import com.vertex.service.farb.service.FarbEventLogger;
import com.vertex.service.farb.signal.FarbSignalCache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 资金费套利 - 出场触发引擎。
 * <p>
 * DESIGN §8：五种触发条件（regime break / basis breach / take profit / max hold / emergency）。
 * 每 30s 遍历所有 OPEN pair 检查是否触发。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExitTriggerEngine {

    private final FarbPositionMapper positionMapper;
    private final FarbStrategyMapper strategyMapper;
    private final FarbSignalCache signalCache;
    private final RegimeMetricsCalculator metricsCalc;
    private final PairExecutor pairExecutor;
    private final FarbEventLogger eventLogger;
    private final ReversalPredictor reversalPredictor;
    private final PredictionState predictionState;

    /** 每 30s 扫一遍 OPEN pairs */
    @Scheduled(fixedDelayString = "PT30S", initialDelayString = "PT2M")
    public void checkAll() {
        List<FarbPosition> openPairs = positionMapper.selectList(new LambdaQueryWrapper<FarbPosition>()
                .eq(FarbPosition::getStatus, "OPEN"));
        if (openPairs.isEmpty()) return;

        for (FarbPosition p : openPairs) {
            try {
                FarbStrategy s = strategyMapper.selectById(p.getStrategyId());
                if (s == null) continue;
                ExitTrigger trigger = evaluate(s, p);
                if (trigger != ExitTrigger.NONE) {
                    fireExit(s, p, trigger);
                }
            } catch (Exception ex) {
                log.warn("[ExitTriggerEngine] check pair={} failed: {}", p.getId(), ex.getMessage());
            }
        }
    }

    /** 按优先级检查五种触发条件（emergency > basis > regime > TP > max hold）*/
    ExitTrigger evaluate(FarbStrategy strategy, FarbPosition pair) {
        // ─── E) Emergency：basis 极端反向 ─────────────────────────────
        var prem = signalCache.latestPremium(pair.getSymbol());
        if (prem != null) {
            double curBasis = prem.value().basisBps();
            double entryBasis = pair.getEntryBasisBps() != null
                    ? pair.getEntryBasisBps().doubleValue() : 0;
            double delta = curBasis - entryBasis;
            double emergencyBps = strategy.getBasisEmergencyBps().doubleValue();
            if (Math.abs(delta) >= emergencyBps) {
                return ExitTrigger.BASIS_EMERGENCY;
            }
            double normalBps = strategy.getExitBasisDeltaBps().doubleValue();
            if (Math.abs(delta) >= normalBps) {
                return ExitTrigger.BASIS_BREACH;
            }
        }

        // ─── B') 预测反转（v2 软/硬两档，opt-in）─────────────────────
        int lookback = strategy.getLookbackSettlements() != null ? strategy.getLookbackSettlements() : 6;
        double minBps = strategy.getMinAvgFundingBps().doubleValue();
        double tpThreshold = strategy.getExitTakeProfitPct().doubleValue();
        int breakCount = strategy.getExitRegimeBreakCount() != null
                ? strategy.getExitRegimeBreakCount() : 2;

        boolean predictOn = strategy.getPredictReversalEnabled() != null
                && strategy.getPredictReversalEnabled() == 1;
        if (predictOn) {
            double healthScore = reversalPredictor.score(pair.getSymbol());
            double hard = strategy.getPredictHardScore() != null
                    ? strategy.getPredictHardScore().doubleValue() : 0.30;
            double soft = strategy.getPredictSoftScore() != null
                    ? strategy.getPredictSoftScore().doubleValue() : 0.60;
            if (healthScore < hard) {
                // 双重确认：预测硬触发 AND 实际最新 funding rate 已转负，才平仓
                // 因为仓位当前仍在赚 funding，只要费率没真反转就继续持有
                var latest = signalCache.recentFunding(pair.getSymbol(), 1);
                double latestBps = latest.isEmpty() ? 0.0
                        : com.vertex.service.farb.regime.RegimeMetricsCalculator.fundingTo8hEqBps(
                                latest.get(0).value().rate(), latest.get(0).value().intervalHours());
                if (latestBps < 0) {
                    log.info("[Predict] HARD trigger + actual reversal sym={} score={} latestBps={} → EXIT",
                            pair.getSymbol(), healthScore, latestBps);
                    return ExitTrigger.PREDICT_REVERSAL;
                }
                // 预测反转但实际还没反 → 保守持有；同时强制走软触发的收紧路径
                long pauseMs = (strategy.getPredictPauseNewMin() != null
                        ? strategy.getPredictPauseNewMin() : 60) * 60_000L;
                predictionState.pauseNewOpens(pair.getSymbol(), pauseMs);
                if (strategy.getPredictTpTightenedPct() != null) {
                    tpThreshold = Math.min(tpThreshold, strategy.getPredictTpTightenedPct().doubleValue());
                }
                if (strategy.getPredictBreakCountTightened() != null) {
                    breakCount = Math.min(breakCount, strategy.getPredictBreakCountTightened());
                }
                log.info("[Predict] HARD trigger but funding still positive sym={} score={} latestBps={} → HOLD (tightened)",
                        pair.getSymbol(), healthScore, latestBps);
            } else
            if (healthScore < soft) {
                // 停开新仓
                long pauseMs = (strategy.getPredictPauseNewMin() != null
                        ? strategy.getPredictPauseNewMin() : 60) * 60_000L;
                predictionState.pauseNewOpens(pair.getSymbol(), pauseMs);
                // 收紧 TP
                if (strategy.getPredictTpTightenedPct() != null) {
                    tpThreshold = Math.min(tpThreshold, strategy.getPredictTpTightenedPct().doubleValue());
                }
                // 收紧 regime break count
                if (strategy.getPredictBreakCountTightened() != null) {
                    breakCount = Math.min(breakCount, strategy.getPredictBreakCountTightened());
                }
                log.debug("[Predict] SOFT trigger sym={} score={} → tightened tp={} break={}",
                        pair.getSymbol(), healthScore, tpThreshold, breakCount);
            }
        }

        // ─── B'') 预结算负 rate（临近结算前预估已反转 → 提前平，避免这一笔负 funding）───
        // 用 Binance 实时推送的 fundingRate（下次结算预估值），比等真实结算快 10-60 分钟
        boolean preSettleOn = strategy.getPreSettleEnabled() == null
                || strategy.getPreSettleEnabled() == 1;
        if (preSettleOn && prem != null) {
            double predictedRate = prem.value().fundingRate();
            long nextSettleTs = prem.value().nextSettleTs();
            long minutesToSettle = nextSettleTs > 0
                    ? (nextSettleTs - System.currentTimeMillis()) / 60_000L
                    : Long.MAX_VALUE;
            double predictedBps = predictedRate * 10000.0;

            int imminentMin = strategy.getPreSettleImminentMin() != null
                    ? strategy.getPreSettleImminentMin() : 10;
            int strongMin = strategy.getPreSettleStrongMin() != null
                    ? strategy.getPreSettleStrongMin() : 60;
            double strongBps = strategy.getPreSettleStrongBps() != null
                    ? strategy.getPreSettleStrongBps().doubleValue() : -3.0;

            if (minutesToSettle > 0 && minutesToSettle <= imminentMin && predictedBps < 0) {
                log.info("[PreSettle] IMMINENT trigger sym={} predictedBps={} minsToSettle={} → EXIT",
                        pair.getSymbol(), predictedBps, minutesToSettle);
                return ExitTrigger.PRE_SETTLE_NEGATIVE;
            }
            if (minutesToSettle > 0 && minutesToSettle <= strongMin && predictedBps <= strongBps) {
                log.info("[PreSettle] STRONG trigger sym={} predictedBps={} minsToSettle={} threshold={} → EXIT",
                        pair.getSymbol(), predictedBps, minutesToSettle, strongBps);
                return ExitTrigger.PRE_SETTLE_NEGATIVE;
            }
        }

        // ─── C) Take Profit （tp<=0 = 禁用，资金费套利建议禁用让 regime 自然出场）─────
        if (tpThreshold > 0) {
            double netPct = computeUnrealizedNetPct(pair);
            if (netPct >= tpThreshold) {
                return ExitTrigger.TAKE_PROFIT;
            }
        }

        // ─── A) Regime Break ──────────────────────────────────────────
        RegimeMetrics m = metricsCalc.compute(pair.getSymbol(), lookback, minBps);
        var recent = signalCache.recentFunding(pair.getSymbol(), breakCount);
        if (recent.size() == breakCount) {
            boolean allBelow = recent.stream().allMatch(e ->
                    RegimeMetricsCalculator.fundingTo8hEqBps(e.value().rate(), e.value().intervalHours())
                            < minBps);
            if (allBelow) {
                return ExitTrigger.REGIME_BREAK;
            }
        }
        // funding 转负也算 regime break
        if (m.getLatestFundingBps() < 0) {
            return ExitTrigger.REGIME_BREAK;
        }

        // ─── D) Max Hold ──────────────────────────────────────────────
        int maxHold = strategy.getExitMaxHoldHours() != null ? strategy.getExitMaxHoldHours() : 168;
        LocalDateTime entryTime = pair.getEntryTime();
        if (entryTime != null && Duration.between(entryTime, LocalDateTime.now()).toHours() >= maxHold) {
            return ExitTrigger.MAX_HOLD_TIMEOUT;
        }

        return ExitTrigger.NONE;
    }

    /**
     * 计算未实现净收益率（%）：funding_pnl_realized + basis_pnl_unrealized − fee_paid_so_far − est_exit_fee
     */
    private double computeUnrealizedNetPct(FarbPosition pair) {
        BigDecimal notional = pair.getNotionalAtEntry() != null ? pair.getNotionalAtEntry() : BigDecimal.ONE;
        BigDecimal fundingPnl = pair.getActualFundingPnl() != null
                ? pair.getActualFundingPnl() : BigDecimal.ZERO;

        var prem = signalCache.latestPremium(pair.getSymbol());
        BigDecimal unrealizedBasis = BigDecimal.ZERO;
        if (prem != null && pair.getEntrySpotPrice() != null && pair.getEntryPerpPrice() != null) {
            double curSpotEst = prem.value().indexPrice();
            double curPerpEst = prem.value().markPrice();
            BigDecimal spotDelta = BigDecimal.valueOf(curSpotEst).subtract(pair.getEntrySpotPrice())
                    .multiply(pair.getSpotQty());
            BigDecimal perpDelta = BigDecimal.valueOf(curPerpEst).subtract(pair.getEntryPerpPrice())
                    .multiply(pair.getPerpQty());
            unrealizedBasis = spotDelta.subtract(perpDelta);
        }

        BigDecimal feePaid = zero(pair.getEntrySpotFee()).add(zero(pair.getEntryPerpFee()));
        // 预估出场手续费 = 入场手续费的 100%（对称）
        BigDecimal estExitFee = feePaid;

        BigDecimal net = fundingPnl.add(unrealizedBasis).subtract(feePaid).subtract(estExitFee);
        return net.divide(notional, 6, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100)).doubleValue();
    }

    private void fireExit(FarbStrategy strategy, FarbPosition pair, ExitTrigger trigger) {
        log.info("[ExitTriggerEngine] fire exit sym={} trigger={} pairId={}",
                pair.getSymbol(), trigger, pair.getId());
        eventLogger.positionInfo(strategy.getId(), pair.getId(), pair.getSymbol(),
                "EXIT_TRIGGER_FIRED", Map.of("trigger", trigger.name()));
        pairExecutor.closePair(strategy, pair, trigger.name());
    }

    private BigDecimal zero(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }

    public enum ExitTrigger {
        NONE,
        REGIME_BREAK,
        BASIS_BREACH,
        BASIS_EMERGENCY,
        TAKE_PROFIT,
        MAX_HOLD_TIMEOUT,
        PREDICT_REVERSAL,
        PRE_SETTLE_NEGATIVE,
        MANUAL,
        EMERGENCY
    }
}

package com.vertex.service.farb.regime;

import com.vertex.service.farb.entity.FarbStrategy;
import org.springframework.stereotype.Component;

/**
 * 资金费套利 - 预期净收益率估算。
 * <p>入场前用于 min_expected_yield_pct filter。</p>
 */
@Component
public class YieldEstimator {

    /**
     * 估算单笔套利的净收益率（%）和年化收益率（%）。
     *
     * @param strategy 策略配置（含 fee 和 slippage 假设）
     * @param m        symbol 的 regime 指标
     * @param assumedHoldHours 假设持仓小时（用近 N 次结算 regime 的持续性估算，简化为 max_hold_hours × 0.6）
     */
    public YieldEstimate estimate(FarbStrategy strategy, RegimeMetrics m, int assumedHoldHours) {
        double notional = strategy.getNotionalPerPair().doubleValue();
        // 手续费：两腿进出共 4 笔
        double spotFee = strategy.getSpotFeeRate().doubleValue();
        double perpFee = strategy.getPerpFeeRate().doubleValue();
        double bnbDiscount = strategy.getUseBnbDiscount() != null && strategy.getUseBnbDiscount() == 1 ? 0.75 : 1.0;
        double feeTotal = 2.0 * (spotFee + perpFee) * bnbDiscount * notional;

        // 滑点：每腿 0.025%，两腿进出共 4 笔
        double slippageTotal = 0.00025 * 4.0 * notional;

        // 预期 funding 收入：假设持仓期间平均 funding = avg_recent × 0.8（保守打折 20% 反映衰减）
        // 结算频次 = holdHours / (funding interval)。这里默认 8h 一次简化，估算次数用 assumedHoldHours / 8。
        double fundingIntervalHours = 8.0;   // 保守假设，v2 可从 FundingHistoryPuller 拿实际值
        int expectedSettlements = Math.max(1, (int) Math.round(assumedHoldHours / fundingIntervalHours));
        // avg 是 8h 归一化 bps；折算为小数 rate = bps / 10000
        double avgRateDecimal = (m.getAvgRecentFundingBps() * 0.8) / 10000.0;
        double fundingRevenue = notional * avgRateDecimal * expectedSettlements;

        double netUsd = fundingRevenue - feeTotal - slippageTotal;
        double netPct = notional > 0 ? netUsd / notional * 100.0 : 0;

        // 年化 = net% × (24 × 365 / assumedHoldHours)
        double annualizedPct = assumedHoldHours > 0 ? netPct * (24.0 * 365.0 / assumedHoldHours) : 0;

        return new YieldEstimate(
                netUsd, netPct, annualizedPct,
                fundingRevenue, feeTotal, slippageTotal,
                expectedSettlements, assumedHoldHours);
    }

    public record YieldEstimate(
            double netUsd,
            double netPct,
            double annualizedPct,
            double fundingRevenueUsd,
            double feeTotalUsd,
            double slippageTotalUsd,
            int expectedSettlements,
            int assumedHoldHours
    ) {}
}

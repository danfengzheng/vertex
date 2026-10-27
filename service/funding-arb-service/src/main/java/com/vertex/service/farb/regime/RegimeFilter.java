package com.vertex.service.farb.regime;

import com.vertex.service.farb.entity.FarbStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 资金费套利 - Stage 1 硬 filter。
 * <p>DESIGN §4.6：任一条件不通过则排除；只做正 funding（§0.2b）。</p>
 */
@Slf4j
@Component
public class RegimeFilter {

    /**
     * 六层硬 filter。用 short-circuit，遇到第一个 reject 就返回原因。
     */
    public FilterResult check(FarbStrategy strategy, RegimeMetrics m) {
        // ─── funding_ok（含"只做正 funding"边界）────────────────────────
        if (m.getFundingSampleCount() == 0) {
            return FilterResult.reject("NO_FUNDING_HISTORY");
        }
        if (m.getAvgRecentFundingBps() <= 0) {
            return FilterResult.reject("NEGATIVE_FUNDING_REGIME");
        }
        if (m.getAvgRecentFundingBps() < strategy.getMinAvgFundingBps().doubleValue()) {
            return FilterResult.reject(String.format("FUNDING_BELOW_THRESHOLD(%.2f<%.2f)",
                    m.getAvgRecentFundingBps(), strategy.getMinAvgFundingBps().doubleValue()));
        }
        if (m.getHitRatio() < strategy.getMinHitRatio().doubleValue()) {
            return FilterResult.reject(String.format("HIT_RATIO_LOW(%.2f<%.2f)",
                    m.getHitRatio(), strategy.getMinHitRatio().doubleValue()));
        }
        if (m.getLatestFundingBps() < strategy.getMinLatestFundingBps().doubleValue()) {
            return FilterResult.reject(String.format("LATEST_FUNDING_LOW(%.2f<%.2f)",
                    m.getLatestFundingBps(), strategy.getMinLatestFundingBps().doubleValue()));
        }

        // ─── lsr_ok ────────────────────────────────────────────────────
        double lsrMax = strategy.getLsrMaxExtreme().doubleValue();
        // LSR = 0 视为数据未到（不 filter）；否则检查极端拥挤（做多不入 lsr > max）
        if (m.getLsr() > 0 && m.getLsr() > lsrMax) {
            return FilterResult.reject(String.format("LSR_EXTREME_LONG(%.2f>%.2f)", m.getLsr(), lsrMax));
        }
        // 极端空头也回避（我们做多 spot + 空 perp，若 basis 反向可能亏更多）
        double lsrMin = 1.0 / lsrMax;  // 3.0 → 0.33
        if (m.getLsr() > 0 && m.getLsr() < lsrMin) {
            return FilterResult.reject(String.format("LSR_EXTREME_SHORT(%.2f<%.2f)", m.getLsr(), lsrMin));
        }

        // ─── oi_ok（避免拥挤性泡沫）─────────────────────────────────────
        double oiCrowd = strategy.getOiCrowdingThresholdPct().doubleValue();
        if (m.getOiChange24hPct() > oiCrowd) {
            return FilterResult.reject(String.format("OI_CROWDED(24h+%.1f%%>%.1f%%)",
                    m.getOiChange24hPct(), oiCrowd));
        }

        // ─── basis_ok ──────────────────────────────────────────────────
        double basisMax = strategy.getMaxEntryBasisBps().doubleValue();
        if (Math.abs(m.getCurrentBasisBps()) > basisMax) {
            return FilterResult.reject(String.format("BASIS_TOO_WIDE(%.1f>%.1f)",
                    Math.abs(m.getCurrentBasisBps()), basisMax));
        }

        // ─── vol_ok（波动率骤升过滤）────────────────────────────────────
        double volSpike = strategy.getVolSpikeMultiplier().doubleValue();
        if (m.getVolSpikeRatio() > 0 && m.getVolSpikeRatio() > volSpike) {
            return FilterResult.reject(String.format("VOL_SPIKE(%.2f>%.2f)",
                    m.getVolSpikeRatio(), volSpike));
        }

        // ─── taker confirm（可选）──────────────────────────────────────
        if (strategy.getRequireTakerConfirm() != null && strategy.getRequireTakerConfirm() == 1) {
            // 我们做多 funding regime，taker buy 应主导（buySellRatio > 1）
            if (m.getTakerBsr() > 0 && m.getTakerBsr() < 1.0) {
                return FilterResult.reject(String.format("TAKER_DIVERGENCE(%.2f<1.0)", m.getTakerBsr()));
            }
        }

        return FilterResult.pass();
    }
}

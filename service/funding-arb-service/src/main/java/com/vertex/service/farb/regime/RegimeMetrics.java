package com.vertex.service.farb.regime;

import lombok.Builder;
import lombok.Data;

/**
 * Regime 判定所需的全部聚合指标。
 * <p>由 {@link RegimeMetricsCalculator} 从 {@code FarbSignalCache} 计算填充。</p>
 */
@Data
@Builder
public class RegimeMetrics {

    private String symbol;

    // ─── Funding 指标 ─────────────────────────────
    /** 近 N 次结算平均 funding（8h 归一化后，bps）*/
    private double avgRecentFundingBps;
    /** 近 N 次中达标次数 / N */
    private double hitRatio;
    /** 最近一次 funding（8h 归一化 bps）*/
    private double latestFundingBps;
    /** funding momentum: 最近 3 次均值 − 更前 3 次均值（判断加速/减速）*/
    private double fundingMomentumBps;
    /** 采样数 */
    private int fundingSampleCount;

    // ─── Basis 指标 ────────────────────────────────
    /** 当前 basis (bps) */
    private double currentBasisBps;
    /** basis 变化率（2h 内 bps 变化）*/
    private double basisDelta2hBps;

    // ─── OI 指标 ──────────────────────────────────
    /** 当前 openInterest */
    private double currentOi;
    /** OI 2h 变化率（%）*/
    private double oiChange2hPct;
    /** OI 24h 变化率（%）*/
    private double oiChange24hPct;

    // ─── LSR ──────────────────────────────────────
    /** 最新 topLongShortPositionRatio */
    private double lsr;

    // ─── Taker BSR ────────────────────────────────
    /** 最新 taker buy/sell ratio */
    private double takerBsr;

    // ─── Realized Vol ─────────────────────────────
    private double vol24hAnnualized;
    private double vol72hAnnualized;
    /** vol24h / vol72h（> 1.5 视为骤升）*/
    private double volSpikeRatio;

    // ─── 数据完整度指示 ────────────────────────────
    /** 是否所有必要指标都有值 */
    private boolean hasCompleteData;
    /** 缺失指标列表（诊断用）*/
    private String missingSignals;
}

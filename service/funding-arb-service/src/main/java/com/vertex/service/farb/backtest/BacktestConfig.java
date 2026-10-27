package com.vertex.service.farb.backtest;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 回测配置。用户在前端表单填这些参数触发一次回测。
 */
@Data
public class BacktestConfig {

    /** 回测起始时间（ms）*/
    private Long startTimeMs;
    /** 回测结束时间（ms） */
    private Long endTimeMs;

    /** 初始资金（USD） */
    private BigDecimal initialCapital = new BigDecimal("10000");

    /** 单对名义 */
    private BigDecimal notionalPerPair = new BigDecimal("500");

    /** Max open pairs（并发）*/
    private int maxOpenPairs = 8;

    /** Max pairs per symbol */
    private int maxPairsPerSymbol = 1;

    // Regime 参数（与实盘一致）
    private int lookbackSettlements = 6;
    private BigDecimal minAvgFundingBps = new BigDecimal("4.0");
    private BigDecimal minHitRatio = new BigDecimal("0.67");
    private BigDecimal minLatestFundingBps = new BigDecimal("2.0");
    private BigDecimal maxEntryBasisBps = new BigDecimal("20.0");

    // 出场参数
    private int exitRegimeBreakCount = 2;
    private BigDecimal exitBasisDeltaBps = new BigDecimal("60.0");
    private BigDecimal exitTakeProfitPct = new BigDecimal("0.8");
    private int exitMaxHoldHours = 168;
    private BigDecimal basisEmergencyBps = new BigDecimal("120.0");

    // 手续费
    private BigDecimal spotFeeRate = new BigDecimal("0.00075");
    private BigDecimal perpFeeRate = new BigDecimal("0.00036");
    private BigDecimal slippagePerLegBps = new BigDecimal("5.0");

    // 币种过滤
    /** 若为 null 或空，用当前 universe（现货 ∩ USDM ∩ 24h vol 达标）*/
    private java.util.List<String> symbolWhitelist;
    private java.util.List<String> symbolBlacklist;

    /** 简化模式：只考虑 funding，不考虑 basis P&L（basis 数据尚未积累时用）*/
    private boolean ignoreBasisPnl = false;

    // ─── 冷静期（避免 exit 后立刻同 tick 再入场，白吃 fee）─────────
    /** REGIME_BREAK 后 symbol 冷静期（小时） */
    private int cooldownRegimeBreakH = 24;
    /** TAKE_PROFIT 后 symbol 冷静期（小时） */
    private int cooldownTakeProfitH = 8;
    /** MAX_HOLD_TIMEOUT 后 symbol 冷静期（小时） */
    private int cooldownMaxHoldH = 4;
    /** BASIS_BREACH / BASIS_EMERGENCY 后 symbol 冷静期（小时） */
    private int cooldownBasisBreachH = 12;
}

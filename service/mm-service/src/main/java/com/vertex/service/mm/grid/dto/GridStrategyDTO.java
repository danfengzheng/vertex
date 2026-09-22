package com.vertex.service.mm.grid.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 创建/更新网格策略请求 —— 前端表单字段。
 * 未列的字段走 Entity 默认值。
 */
@Data
public class GridStrategyDTO {

    private String name;
    private String description;
    private String exchange;
    private String symbol;
    private Long accountId;

    // ─── V1 简化版新增（DESIGN_V1_SIMPLE.md）─────────────
    /** LONG_ONLY / SHORT_ONLY / BOTH */
    private String side;
    /** ARITHMETIC / GEOMETRIC */
    private String gridMode;
    private BigDecimal lowerPrice;
    private BigDecimal upperPrice;
    private Integer gridCount;

    private Integer windowSizePerSide;
    private BigDecimal qtyPerGrid;
    private BigDecimal baseStepPct;
    private BigDecimal minimumStepSafetyMultiplier;

    private BigDecimal maxPositionQty;
    private BigDecimal maxNotionalUsd;
    private BigDecimal maxDeltaQty;

    private Integer hedgeMode;
    private Integer longSideEnabled;
    private Integer shortSideEnabled;
    private Integer adaptiveStepEnabled;
    private Integer trendProtectionEnabled;

    private String trendLayer1Interval;
    private String trendLayer2Interval;
    private String trendLayer3Interval;
    private String trendLayer4Interval;

    private BigDecimal trendLayer2AdxEnter;
    private BigDecimal trendLayer2AdxExit;
    private BigDecimal trendLayer3AdxEnter;
    private BigDecimal trendLayer3AdxExit;
    private BigDecimal trendLayer4AdxEnter;

    private BigDecimal longReducePnlPct;
    private BigDecimal shortReducePnlPct;
    private BigDecimal longStopPnlPct;
    private BigDecimal shortStopPnlPct;
    private BigDecimal accountEmergencyPnlPct;
    /** 账户级 pnl 自动 emergencyStop 开关；0=关(默认) 1=开 */
    private Integer riskAutoStopEnabled;

    private BigDecimal ladderPctOfPosition;
    private Integer ladderStepCount;
    private BigDecimal ladderMinReservePct;

    private Integer reshuffleCooldownSec;
    private Integer emergencyTakerFallbackEnabled;
    private BigDecimal emergencyTakerLossThreshold;

    private Integer leverage;
    private String marginType;

    // ─── Paper Trading + Smart Pricing（V30 加）─────────
    /** PAPER (模拟成交) / LIVE (真实下单)；不填走 entity 默认 PAPER */
    private String executionMode;
    /** 排队损失系数 0.3-1.0（0.5=中性，1.0=乐观，0.3=悲观）*/
    private BigDecimal paperQueueLossFactor;
    /** 模拟 GTX 拒绝率 0-1 */
    private BigDecimal paperGtxRejectRate;
    /** SmartPricePlacer 开关（PAPER + LIVE 都可用）*/
    private Integer paperSmartPricingEnabled;
    /** 薄簿位判定阈值（BTC 建议 0.5 / ETH 建议 2-5）*/
    private BigDecimal paperMinLiquidityQty;
}

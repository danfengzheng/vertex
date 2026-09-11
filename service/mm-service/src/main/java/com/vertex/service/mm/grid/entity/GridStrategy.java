package com.vertex.service.mm.grid.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.vertex.common.core.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 做市模块 - 网格策略主表。
 * <p>
 * 每个 (exchange, symbol, account) 组合一个实例。字段跟 V29_mm_grid_module.sql 对齐。
 * </p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("grid_strategy")
public class GridStrategy extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String name;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String description;

    private String exchange;
    private String symbol;
    private Long accountId;

    /** STOPPED / RUNNING / PAUSED / EMERGENCY_STOPPED */
    private String status;

    // ─── 网格几何 ─────────────────────────────────
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private BigDecimal centerPrice;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private BigDecimal stepPct;
    private Integer windowSizePerSide;
    private BigDecimal qtyPerGrid;

    private BigDecimal baseStepPct;
    private BigDecimal minimumStepSafetyMultiplier;

    // ─── 风控上限 ─────────────────────────────────
    private BigDecimal maxPositionQty;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private BigDecimal maxNotionalUsd;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private BigDecimal maxDeltaQty;

    // ─── 模式开关 ─────────────────────────────────
    private Integer hedgeMode;
    private Integer longSideEnabled;
    private Integer shortSideEnabled;
    private Integer adaptiveStepEnabled;
    private Integer trendProtectionEnabled;

    // ─── 分周期趋势 ───────────────────────────────
    private String trendLayer1Interval;
    private String trendLayer2Interval;
    private String trendLayer3Interval;
    private String trendLayer4Interval;

    // ─── 分层 ADX 阈值 ─────────────────────────────
    private BigDecimal trendLayer2AdxEnter;
    private BigDecimal trendLayer2AdxExit;
    private BigDecimal trendLayer3AdxEnter;
    private BigDecimal trendLayer3AdxExit;
    private BigDecimal trendLayer4AdxEnter;

    // ─── 波动率 ATR ────────────────────────────────
    private Integer volAtrPeriod;
    private Integer volAtrBaselinePeriod;

    // ─── Depletion 判据 ───────────────────────────
    private BigDecimal depletionPctLayer2;
    private BigDecimal depletionPctLayer3;
    private Integer depletionTimeLayer2Sec;
    private Integer depletionTimeLayer3Sec;

    // ─── 综合评分权重 ──────────────────────────────
    private Integer scoreWeightAdx;
    private Integer scoreWeightDepletion;
    private Integer scoreWeightAsymmetry;
    private Integer scoreWeightConsecutive;
    private Integer scoreWeightReverseGap;
    private Integer scoreLayer1Min;
    private Integer scoreLayer2Min;
    private Integer scoreLayer3Min;
    private Integer scoreLayer4Min;

    // ─── 分侧减仓/停机 ─────────────────────────────
    private BigDecimal longReducePnlPct;
    private BigDecimal shortReducePnlPct;
    private BigDecimal longStopPnlPct;
    private BigDecimal shortStopPnlPct;
    private BigDecimal accountEmergencyPnlPct;
    private BigDecimal accountMarginUsagePct;

    /** 账户级 pnl 阈值自动 emergencyStop 开关；0=关(默认), 1=开
     *  关闭时 accountEmergencyPnlPct 不触发；币安 MARGIN_CALL 事件仍强制触发 */
    private Integer riskAutoStopEnabled;

    // ─── 梯队平仓 ─────────────────────────────────
    private BigDecimal ladderPctOfPosition;
    private Integer ladderStepCount;
    private Integer ladderFloorAtAvgCost;
    private BigDecimal ladderMinReservePct;

    // ─── Reshuffle / 冷却 ─────────────────────────
    private Integer reshufflePriceMoveGrids;
    private Integer reshuffleCooldownSec;
    private Integer minModeChangeIntervalSec;
    private Integer layer3MinHoldSec;
    private Integer layer4MinHoldSec;
    private BigDecimal rapidMovePausePct;

    // ─── 紧急平仓 ─────────────────────────────────
    private Integer emergencyTakerFallbackEnabled;
    private BigDecimal emergencyTakerLossThreshold;
    private Integer gracefulCloseRepricingIntervalSec;

    // ─── 杠杆 ─────────────────────────────────────
    private Integer leverage;
    private String marginType;

    // ─── Paper Trading + Smart Pricing ─────────────
    /** PAPER (模拟成交) / LIVE (真实下单) */
    private String executionMode;
    /** 排队损失系数 (0.3-1.0)，模拟填充比例 */
    private BigDecimal paperQueueLossFactor;
    /** 模拟 GTX 拒绝率 (0-1) */
    private BigDecimal paperGtxRejectRate;
    /** 是否启用智能挂单价（Paper + Live 都可用）*/
    private Integer paperSmartPricingEnabled;
    /** 薄簿位判定阈值 */
    private BigDecimal paperMinLiquidityQty;

    // ─── 运行时快照 ───────────────────────────────
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime lastReshuffleAt;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime lastAdaptiveAt;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime lastLayerChangeAt;
    private String currentActiveLayer;
    private BigDecimal currentLongQty;
    private BigDecimal currentShortQty;
    private BigDecimal totalArbitragePnl;
    private BigDecimal totalDirectionalPnl;
    private BigDecimal totalFeePaid;
}

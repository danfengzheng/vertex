package com.vertex.service.farb.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 资金费套利持仓（一对 = 现货多头 + 期货空头）。
 */
@Data
@TableName("farb_position")
public class FarbPosition {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long strategyId;
    private String symbol;
    /** PENDING_ENTRY / OPEN / CLOSING / CLOSED / EMERGENCY_CLOSED / ZOMBIE */
    private String status;

    // ─── 入场 ─────────────────────────────────
    private LocalDateTime entryTime;
    private BigDecimal entrySpotPrice;
    private BigDecimal entryPerpPrice;
    private BigDecimal entryBasisBps;
    private BigDecimal entryFundingRate;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String entryRegimeSnapshot;
    private BigDecimal projectedNetYieldPct;
    private Integer projectedRegimeHours;

    private BigDecimal spotQty;
    private BigDecimal perpQty;
    private BigDecimal notionalAtEntry;

    private String entrySpotOrderId;
    private String entrySpotClientId;
    private String entryPerpOrderId;
    private String entryPerpClientId;
    private BigDecimal entrySpotFee;
    private BigDecimal entryPerpFee;

    // ─── 结算追踪 ─────────────────────────────
    private LocalDateTime nextExpectedSettlementTime;
    private LocalDateTime lastSettlementTime;
    private Integer settlementCount;
    private BigDecimal actualFundingPnl;

    // ─── 出场 ─────────────────────────────────
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime closeTime;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String closeTrigger;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private BigDecimal exitSpotPrice;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private BigDecimal exitPerpPrice;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private BigDecimal exitBasisBps;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String exitSpotOrderId;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String exitPerpOrderId;
    private BigDecimal exitSpotFee;
    private BigDecimal exitPerpFee;

    // ─── P&L 归因 ─────────────────────────────
    private BigDecimal fundingPnl;
    private BigDecimal basisPnl;
    private BigDecimal feePaid;
    private BigDecimal netPnl;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}

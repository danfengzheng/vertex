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
 * 做市模块 - 挂单快照 + 成交流水。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("grid_order")
public class GridOrder extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long gridStrategyId;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String exchangeOrderId;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String clientOrderId;

    /** BUY / SELL */
    private String side;
    /** LONG / SHORT (hedge mode positionSide) */
    private String positionSide;
    /** OPEN / CLOSE / LADDER_CLOSE */
    private String role;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Integer gridIndex;
    private BigDecimal gridPrice;
    private BigDecimal qty;

    /** PENDING / LIVE / FILLED / PARTIAL / CANCELED / REJECTED */
    private String status;
    private BigDecimal filledQty;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private BigDecimal avgFillPrice;
    private BigDecimal feePaid;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Long pairOrderId;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime placedAt;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime filledAt;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime canceledAt;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String rejectReason;
}

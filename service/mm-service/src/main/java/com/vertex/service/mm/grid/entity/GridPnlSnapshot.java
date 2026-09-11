package com.vertex.service.mm.grid.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 做市模块 - P&L 快照（分钟级采样，用于绘制曲线 + P&L 归因）。
 */
@Data
@TableName("grid_pnl_snapshot")
public class GridPnlSnapshot implements Serializable {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long gridStrategyId;
    private LocalDateTime snapshotAt;

    private BigDecimal markPrice;
    private BigDecimal longQty;
    private BigDecimal shortQty;
    private BigDecimal netQty;
    private BigDecimal netNotional;
    private BigDecimal longAvgCost;
    private BigDecimal shortAvgCost;

    private BigDecimal unrealizedPnl;
    private BigDecimal realizedArbitragePnl;
    private BigDecimal realizedDirectionalPnl;
    private BigDecimal fundingPnl;
    private BigDecimal feePaid;

    private Integer roundTripsCompleted;
    private Integer activeOrdersCount;
    private BigDecimal longDepletionPct;
    private BigDecimal shortDepletionPct;
    private String currentLayer;
    private Integer trendScore;
}

package com.vertex.model.vo.trading;

import com.vertex.model.entity.trading.ExecutionMode;
import com.vertex.model.entity.trading.MarginType;
import com.vertex.model.entity.trading.MarketType;
import com.vertex.model.entity.trading.PositionSide;
import com.vertex.model.entity.trading.PositionStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 持仓 VO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PositionVO implements Serializable {

    private Long id;
    private Long strategyId;
    private String strategyName;
    private Long accountId;
    private String accountName;
    private String exchange;
    private String symbol;
    private PositionSide side;
    private BigDecimal quantity;
    private BigDecimal entryPrice;
    private BigDecimal currentPrice;
    private BigDecimal unrealizedPnl;
    private BigDecimal realizedPnl;
    private BigDecimal stopLoss;
    private BigDecimal takeProfit;
    private BigDecimal closePrice;
    private LocalDateTime closedAt;
    private PositionStatus status;
    private ExecutionMode tradeMode;

    // ─── 合约专用字段 ─────────────────────────────────
    private MarketType marketType;
    private Integer leverage;
    private MarginType marginType;
    private BigDecimal liquidationPrice;
    private BigDecimal fundingRate;

    /**
     * SuperTrend 动态止损价（null = 未启用或未触发首次更新）。
     * 由 StrategyEngineService 在每根 K 线收盘后写入，用于前端展示动态止损价。
     */
    private BigDecimal superTrendStopLoss;

    // ─── 分阶段止盈进度（null/0 = 未触发） ───────────────────────────
    /** 已触发档数（0/1/2/3） */
    private Integer takeProfitStage;
    /** 持仓建立时原始数量，用于前端展示「累计平仓百分比 = (initialQuantity - quantity) / initialQuantity」 */
    private BigDecimal initialQuantity;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}

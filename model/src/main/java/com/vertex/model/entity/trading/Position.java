package com.vertex.model.entity.trading;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.vertex.common.core.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 持仓实体
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("trd_position")
public class Position extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 关联策略ID */
    private Long strategyId;

    /** 关联账户ID */
    private Long accountId;

    /** 交易所 */
    private String exchange;

    /** 交易对 */
    private String symbol;

    /** 持仓方向 LONG/SHORT */
    private PositionSide side;

    /** 持仓数量 */
    private BigDecimal quantity;

    /** 开仓均价 */
    private BigDecimal entryPrice;

    /** 当前价格 */
    private BigDecimal currentPrice;

    /** 未实现盈亏 */
    private BigDecimal unrealizedPnl;

    /** 已实现盈亏 */
    private BigDecimal realizedPnl;

    /** 止损价 */
    private BigDecimal stopLoss;

    /** 止盈价 */
    private BigDecimal takeProfit;

    /** 持仓状态 OPEN/CLOSED */
    private PositionStatus status;

    /** 交易模式 LIVE/PAPER */
    private ExecutionMode tradeMode;
}

package com.vertex.model.entity.trading;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.vertex.common.core.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 交易订单实体
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("trd_order")
public class Order extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 关联策略ID */
    private Long strategyId;

    /** 关联账户ID */
    private Long accountId;

    /** 关联信号ID */
    private Long signalId;

    /** 交易所 */
    private String exchange;

    /** 交易对 */
    private String symbol;

    /** 方向 BUY/SELL */
    private OrderSide side;

    /** 订单类型 MARKET/LIMIT */
    private OrderType orderType;

    /** 下单数量 */
    private BigDecimal quantity;

    /** 委托价格（LIMIT 订单） */
    private BigDecimal price;

    /** 成交数量 */
    private BigDecimal filledQuantity;

    /** 成交均价 */
    private BigDecimal filledPrice;

    /** 手续费 */
    private BigDecimal fee;

    /** 订单状态 */
    private OrderStatus status;

    /** 交易模式 LIVE/PAPER */
    private ExecutionMode tradeMode;

    /** 交易所订单号 */
    private String exchangeOrderId;

    /** 错误信息 */
    private String errorMsg;
}

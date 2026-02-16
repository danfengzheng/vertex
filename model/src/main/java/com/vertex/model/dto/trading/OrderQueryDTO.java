package com.vertex.model.dto.trading;

import com.vertex.model.entity.trading.ExecutionMode;
import com.vertex.model.entity.trading.OrderSide;
import com.vertex.model.entity.trading.OrderStatus;
import com.vertex.model.query.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 订单分页查询参数
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class OrderQueryDTO extends PageQuery {

    private Long strategyId;

    private String exchange;

    private String symbol;

    private OrderSide side;

    private OrderStatus status;

    private ExecutionMode tradeMode;

    /** 开始时间 */
    private Long startTime;

    /** 结束时间 */
    private Long endTime;
}

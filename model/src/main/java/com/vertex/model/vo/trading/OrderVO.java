package com.vertex.model.vo.trading;

import com.vertex.model.entity.trading.ExecutionMode;
import com.vertex.model.entity.trading.OrderSide;
import com.vertex.model.entity.trading.OrderStatus;
import com.vertex.model.entity.trading.OrderType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 交易订单 VO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderVO implements Serializable {

    private Long id;
    private Long strategyId;
    private String strategyName;
    private Long accountId;
    private String accountName;
    private Long signalId;
    private String exchange;
    private String symbol;
    private OrderSide side;
    private OrderType orderType;
    private BigDecimal quantity;
    private BigDecimal price;
    private BigDecimal filledQuantity;
    private BigDecimal filledPrice;
    private BigDecimal fee;
    private OrderStatus status;
    private ExecutionMode tradeMode;
    private String exchangeOrderId;
    private String errorMsg;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}

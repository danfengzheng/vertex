package com.vertex.model.dto.trading;

import com.vertex.model.entity.trading.ExecutionMode;
import com.vertex.model.entity.trading.OrderSide;
import com.vertex.model.entity.trading.OrderType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 手动下单参数
 */
@Data
public class OrderCreateDTO {

    @NotNull(message = "账户ID不能为空")
    private Long accountId;

    @NotBlank(message = "交易所不能为空")
    private String exchange;

    @NotBlank(message = "交易对不能为空")
    private String symbol;

    @NotNull(message = "方向不能为空")
    private OrderSide side;

    /** 订单类型，默认 MARKET */
    private OrderType orderType = OrderType.MARKET;

    @NotNull(message = "数量不能为空")
    @Positive(message = "数量必须大于0")
    private BigDecimal quantity;

    /** 委托价格（LIMIT 订单必填） */
    private BigDecimal price;

    /** 执行模式，默认 PAPER */
    private ExecutionMode executionMode = ExecutionMode.PAPER;
}

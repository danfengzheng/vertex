package com.vertex.model.entity.trading;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 订单状态
 */
@Getter
@AllArgsConstructor
public enum OrderStatus {

    PENDING("PENDING", "待确认"),
    SUBMITTED("SUBMITTED", "已提交"),
    FILLED("FILLED", "已成交"),
    PARTIALLY_FILLED("PARTIALLY_FILLED", "部分成交"),
    CANCELLED("CANCELLED", "已取消"),
    REJECTED("REJECTED", "已拒绝"),
    EXPIRED("EXPIRED", "已过期"),
    SIMULATED("SIMULATED", "模拟成交");

    private final String code;
    private final String description;
}

package com.vertex.model.entity.trading;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 订单类型
 */
@Getter
@AllArgsConstructor
public enum OrderType {

    MARKET("MARKET", "市价"),
    LIMIT("LIMIT", "限价");

    private final String code;
    private final String description;
}

package com.vertex.model.entity.trading;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 交易模式
 */
@Getter
@AllArgsConstructor
public enum TradeMode {

    AUTO("AUTO", "自动执行"),
    MANUAL("MANUAL", "手动确认");

    private final String code;
    private final String description;
}

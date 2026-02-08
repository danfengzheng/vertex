package com.vertex.model.entity.strategy;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 信号类型
 */
@Getter
@AllArgsConstructor
public enum SignalType {

    BUY("BUY", "买入信号"),
    SELL("SELL", "卖出信号"),
    NEUTRAL("NEUTRAL", "中性信号");

    private final String code;
    private final String description;
}

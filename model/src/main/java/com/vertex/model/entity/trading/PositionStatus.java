package com.vertex.model.entity.trading;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 持仓状态
 */
@Getter
@AllArgsConstructor
public enum PositionStatus {

    OPEN("OPEN", "持仓中"),
    CLOSED("CLOSED", "已平仓");

    private final String code;
    private final String description;
}

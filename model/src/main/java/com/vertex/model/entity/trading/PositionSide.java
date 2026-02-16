package com.vertex.model.entity.trading;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 持仓方向
 */
@Getter
@AllArgsConstructor
public enum PositionSide {

    LONG("LONG", "多头"),
    SHORT("SHORT", "空头");

    private final String code;
    private final String description;
}

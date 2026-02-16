package com.vertex.model.entity.trading;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 执行模式
 */
@Getter
@AllArgsConstructor
public enum ExecutionMode {

    LIVE("LIVE", "实盘"),
    PAPER("PAPER", "模拟");

    private final String code;
    private final String description;
}

package com.vertex.model.entity.trading;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 账户状态
 */
@Getter
@AllArgsConstructor
public enum AccountStatus {

    DISABLED(0, "禁用"),
    NORMAL(1, "正常");

    private final Integer code;
    private final String description;
}

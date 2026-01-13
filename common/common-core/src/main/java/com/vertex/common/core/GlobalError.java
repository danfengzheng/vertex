package com.vertex.common.core;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * GlobalError
 *
 * @author eth
 * @version 1.0
 * @description
 * @date 2026/1/13 23:38
 */
@AllArgsConstructor
public enum GlobalError implements ErrorCode {

    SUCCESS(200, "操作成功"),
    SYSTEM_ERROR(500, "系统异常"),
    PARAM_ERROR(400, "参数错误"),
    NOT_FOUND(404, "资源不存在"),
    UNAUTHORIZED(401, "未授权"),
    FORBIDDEN(403, "无权限"),

    // 业务异常
    USER_NOT_EXIST(1001, "用户不存在"),
    USER_ALREADY_EXIST(1002, "用户已存在"),
    ORDER_NOT_EXIST(2001, "订单不存在"),
    INSUFFICIENT_STOCK(2002, "库存不足");
    @Getter
    private final Integer code;
    @Getter
    private final String message;
}

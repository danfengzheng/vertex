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
    INSUFFICIENT_STOCK(2002, "库存不足"),

    // 行情异常
    KLINE_NOT_FOUND(3001, "K线数据不存在"),
    KLINE_STORE_ERROR(3002, "K线存储异常"),
    EXCHANGE_CONNECT_ERROR(3003, "交易所连接异常"),
    QUOTE_DATA_CONVERT_ERROR(3004, "行情数据转换异常"),

    // 策略异常
    STRATEGY_NOT_FOUND(4001, "策略不存在"),
    STRATEGY_ALREADY_EXISTS(4002, "策略名称已存在"),
    STRATEGY_CONFIG_ERROR(4003, "策略配置错误"),
    SIGNAL_NOT_FOUND(4005, "信号不存在"),
    SIGNAL_STORE_ERROR(4006, "信号存储异常"),
    INDICATOR_CALC_ERROR(4007, "指标计算异常"),
    STRATEGY_KLINE_INSUFFICIENT(4008, "K线数据不足");
    @Getter
    private final Integer code;
    @Getter
    private final String message;
}

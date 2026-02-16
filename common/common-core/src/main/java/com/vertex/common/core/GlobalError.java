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
    STRATEGY_KLINE_INSUFFICIENT(4008, "K线数据不足"),
    BACKTEST_INSUFFICIENT_DATA(4009, "回测数据不足，请先通过历史补全功能获取K线数据"),

    // 交易异常
    ACCOUNT_NOT_FOUND(5001, "交易账户不存在"),
    ACCOUNT_DISABLED(5002, "交易账户已禁用"),
    ACCOUNT_NAME_EXISTS(5003, "账户名称已存在"),
    TRADE_ORDER_NOT_FOUND(5004, "交易订单不存在"),
    TRADE_ORDER_CANNOT_CANCEL(5005, "当前订单状态无法取消"),
    TRADE_INSUFFICIENT_BALANCE(5006, "账户余额不足"),
    TRADE_EXECUTION_FAILED(5007, "交易执行失败"),
    TRADE_API_ERROR(5008, "交易所API调用异常"),
    TRADE_ENCRYPTION_ERROR(5009, "密钥加解密异常"),
    POSITION_NOT_FOUND(5010, "持仓不存在"),
    POSITION_ALREADY_CLOSED(5011, "持仓已关闭"),
    TRADE_QUANTITY_INVALID(5012, "交易数量无效");
    @Getter
    private final Integer code;
    @Getter
    private final String message;
}

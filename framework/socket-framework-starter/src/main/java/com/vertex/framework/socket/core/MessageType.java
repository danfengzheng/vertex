package com.vertex.framework.socket.core;

/**
 * 消息类型枚举
 */
public enum MessageType {

    /** 订阅 */
    SUBSCRIBE,

    /** 取消订阅 */
    UNSUBSCRIBE,

    /** 数据推送 */
    DATA,

    /** 心跳 */
    HEARTBEAT,

    /** 错误 */
    ERROR,

    /** 请求 */
    REQUEST,

    /** 响应 */
    RESPONSE
}

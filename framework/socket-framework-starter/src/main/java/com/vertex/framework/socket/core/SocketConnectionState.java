package com.vertex.framework.socket.core;

/**
 * 连接状态枚举
 */
public enum SocketConnectionState {

    /** 连接中 */
    CONNECTING,

    /** 已连接 */
    CONNECTED,

    /** 断开中 */
    DISCONNECTING,

    /** 已断开 */
    DISCONNECTED,

    /** 重连中 */
    RECONNECTING
}

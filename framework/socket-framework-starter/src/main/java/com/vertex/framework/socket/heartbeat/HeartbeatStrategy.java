package com.vertex.framework.socket.heartbeat;

import io.netty.channel.Channel;

/**
 * 心跳策略接口
 * <p>
 * 不同场景（内部通信、交易所对接）有不同的心跳协议：
 * - 标准 WebSocket Ping/Pong Frame
 * - 币安：需要回复 pong 文本
 * - OKX：需要发送 "ping" 文本
 */
public interface HeartbeatStrategy {

    /**
     * 发送心跳
     */
    void sendHeartbeat(Channel channel);

    /**
     * 判断消息是否为心跳响应
     */
    boolean isHeartbeatResponse(String message);

    /**
     * 处理心跳响应
     */
    void handleHeartbeatResponse(Channel channel, String message);
}

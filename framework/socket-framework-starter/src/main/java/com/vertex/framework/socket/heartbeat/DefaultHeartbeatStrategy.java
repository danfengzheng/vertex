package com.vertex.framework.socket.heartbeat;

import io.netty.channel.Channel;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import lombok.extern.slf4j.Slf4j;

/**
 * 默认心跳策略：使用标准 WebSocket Ping/Pong Frame
 */
@Slf4j
public class DefaultHeartbeatStrategy implements HeartbeatStrategy {

    @Override
    public void sendHeartbeat(Channel channel) {
        if (channel.isActive()) {
            channel.writeAndFlush(new PingWebSocketFrame());
            log.debug("Sent ping to {}", channel.remoteAddress());
        }
    }

    @Override
    public boolean isHeartbeatResponse(String message) {
        return "pong".equalsIgnoreCase(message);
    }

    @Override
    public void handleHeartbeatResponse(Channel channel, String message) {
        log.debug("Received pong from {}", channel.remoteAddress());
    }
}

package com.vertex.framework.socket.core;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.util.AttributeKey;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket 会话封装
 * <p>
 * 对 Netty Channel 的封装，提供发送、关闭、属性存取等能力
 */
@Slf4j
public class SocketSession {

    @Getter
    private final String id;

    @Getter
    private final Channel channel;

    private final Map<String, Object> attributes = new ConcurrentHashMap<>();

    @Getter
    private volatile SocketConnectionState state;

    public SocketSession(Channel channel) {
        this.id = channel.id().asLongText();
        this.channel = channel;
        this.state = SocketConnectionState.CONNECTED;
    }

    /**
     * 发送文本消息
     */
    public ChannelFuture send(String text) {
        if (!isActive()) {
            log.warn("Session {} is not active, cannot send message", id);
            return channel.newFailedFuture(new IllegalStateException("Session is not active"));
        }
        return channel.writeAndFlush(new TextWebSocketFrame(text));
    }

    /**
     * 关闭会话
     */
    public ChannelFuture close() {
        this.state = SocketConnectionState.DISCONNECTING;
        return channel.close().addListener(future -> {
            this.state = SocketConnectionState.DISCONNECTED;
            log.info("Session {} closed", id);
        });
    }

    /**
     * 会话是否活跃
     */
    public boolean isActive() {
        return channel.isActive() && state == SocketConnectionState.CONNECTED;
    }

    /**
     * 设置属性
     */
    public void setAttribute(String key, Object value) {
        attributes.put(key, value);
    }

    /**
     * 获取属性
     */
    @SuppressWarnings("unchecked")
    public <T> T getAttribute(String key) {
        return (T) attributes.get(key);
    }

    /**
     * 移除属性
     */
    public void removeAttribute(String key) {
        attributes.remove(key);
    }

    /**
     * 从 Channel 中获取 SocketSession
     */
    public static final AttributeKey<SocketSession> SESSION_KEY = AttributeKey.valueOf("socketSession");

    public static SocketSession getFromChannel(Channel channel) {
        return channel.attr(SESSION_KEY).get();
    }

    public static void bindToChannel(Channel channel, SocketSession session) {
        channel.attr(SESSION_KEY).set(session);
    }

    public void setState(SocketConnectionState state) {
        this.state = state;
    }
}

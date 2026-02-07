package com.vertex.framework.socket.event;

import com.vertex.framework.socket.core.SocketConnectionState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Socket 事件发布器
 * <p>
 * 桥接 Netty 事件到 Spring ApplicationEvent 体系
 */
@Slf4j
public class SocketEventPublisher {

    private final ApplicationEventPublisher eventPublisher;

    public SocketEventPublisher(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    /**
     * 发布连接事件
     */
    public void publishConnectionEvent(Object source, String sessionId,
                                       SocketConnectionState state, String remoteAddress) {
        ConnectionEvent event = new ConnectionEvent(source, sessionId, state, remoteAddress);
        log.debug("Publishing connection event: session={}, state={}", sessionId, state);
        eventPublisher.publishEvent(event);
    }

    /**
     * 发布消息事件
     */
    public void publishMessageEvent(Object source, String sessionId, String topic, String payload) {
        MessageEvent event = new MessageEvent(source, sessionId, topic, payload);
        log.debug("Publishing message event: session={}, topic={}", sessionId, topic);
        eventPublisher.publishEvent(event);
    }
}

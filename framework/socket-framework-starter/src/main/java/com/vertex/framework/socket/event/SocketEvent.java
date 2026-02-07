package com.vertex.framework.socket.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/**
 * Socket 基础事件
 */
@Getter
public abstract class SocketEvent extends ApplicationEvent {

    private final String sessionId;

    protected SocketEvent(Object source, String sessionId) {
        super(source);
        this.sessionId = sessionId;
    }
}

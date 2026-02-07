package com.vertex.framework.socket.event;

import lombok.Getter;

/**
 * 消息接收事件
 */
@Getter
public class MessageEvent extends SocketEvent {

    private final String topic;
    private final String payload;

    public MessageEvent(Object source, String sessionId, String topic, String payload) {
        super(source, sessionId);
        this.topic = topic;
        this.payload = payload;
    }
}

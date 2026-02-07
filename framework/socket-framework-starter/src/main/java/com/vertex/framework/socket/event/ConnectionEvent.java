package com.vertex.framework.socket.event;

import com.vertex.framework.socket.core.SocketConnectionState;
import lombok.Getter;

/**
 * 连接事件（连接/断开/重连）
 */
@Getter
public class ConnectionEvent extends SocketEvent {

    private final SocketConnectionState state;
    private final String remoteAddress;

    public ConnectionEvent(Object source, String sessionId, SocketConnectionState state, String remoteAddress) {
        super(source, sessionId);
        this.state = state;
        this.remoteAddress = remoteAddress;
    }
}

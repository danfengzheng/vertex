package com.vertex.framework.socket.core;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 统一消息模型
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SocketMessage implements Serializable {

    /** 消息类型 */
    private MessageType type;

    /** 主题/频道 */
    private String topic;

    /** 消息负载（JSON 字符串或原始数据） */
    private String payload;

    /** 消息时间戳 */
    private Long timestamp;

    /** 消息ID（用于请求-响应匹配） */
    private String id;

    public static SocketMessage heartbeat() {
        return SocketMessage.builder()
                .type(MessageType.HEARTBEAT)
                .timestamp(System.currentTimeMillis())
                .build();
    }

    public static SocketMessage data(String topic, String payload) {
        return SocketMessage.builder()
                .type(MessageType.DATA)
                .topic(topic)
                .payload(payload)
                .timestamp(System.currentTimeMillis())
                .build();
    }

    public static SocketMessage subscribe(String topic) {
        return SocketMessage.builder()
                .type(MessageType.SUBSCRIBE)
                .topic(topic)
                .timestamp(System.currentTimeMillis())
                .build();
    }

    public static SocketMessage unsubscribe(String topic) {
        return SocketMessage.builder()
                .type(MessageType.UNSUBSCRIBE)
                .topic(topic)
                .timestamp(System.currentTimeMillis())
                .build();
    }

    public static SocketMessage error(String message) {
        return SocketMessage.builder()
                .type(MessageType.ERROR)
                .payload(message)
                .timestamp(System.currentTimeMillis())
                .build();
    }
}

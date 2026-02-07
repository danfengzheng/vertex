package com.vertex.framework.socket.client;

import com.vertex.framework.socket.codec.MessageCodec;
import com.vertex.framework.socket.heartbeat.HeartbeatStrategy;
import com.vertex.framework.socket.reconnect.ReconnectPolicy;
import lombok.Builder;
import lombok.Data;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

/**
 * WebSocket 客户端配置
 */
@Data
@Builder
public class WebSocketClientConfig {

    /** WebSocket 连接地址 */
    private URI uri;

    /** 自定义请求头 */
    @Builder.Default
    private Map<String, String> headers = new HashMap<>();

    /** 是否启用 SSL */
    @Builder.Default
    private boolean ssl = false;

    /** 心跳间隔（秒），0 表示不启用心跳 */
    @Builder.Default
    private int heartbeatIntervalSeconds = 30;

    /** 最大丢失心跳次数 */
    @Builder.Default
    private int maxMissedHeartbeats = 3;

    /** 心跳策略 */
    private HeartbeatStrategy heartbeatStrategy;

    /** 重连策略 */
    private ReconnectPolicy reconnectPolicy;

    /** 消息编解码器 */
    private MessageCodec messageCodec;

    /** 连接超时（毫秒） */
    @Builder.Default
    private int connectTimeoutMs = 10000;

    /** 最大帧大小（字节） */
    @Builder.Default
    private int maxFrameSize = 65536;

    /** 工作线程数 */
    @Builder.Default
    private int workerThreads = 2;

    /** 是否启用自动重连 */
    @Builder.Default
    private boolean autoReconnect = true;
}

package com.vertex.framework.socket.server;

import lombok.Builder;
import lombok.Data;

/**
 * WebSocket 服务端配置
 */
@Data
@Builder
public class WebSocketServerConfig {

    /** 监听端口 */
    @Builder.Default
    private int port = 9090;

    /** WebSocket 路径 */
    @Builder.Default
    private String path = "/ws";

    /** Boss 线程数 */
    @Builder.Default
    private int bossThreads = 1;

    /** Worker 线程数 */
    @Builder.Default
    private int workerThreads = 4;

    /** 最大帧大小（字节） */
    @Builder.Default
    private int maxFrameSize = 65536;

    /** 心跳间隔（秒），0 表示不启用 */
    @Builder.Default
    private int heartbeatIntervalSeconds = 60;

    /** 最大连接数，0 表示不限 */
    @Builder.Default
    private int maxConnections = 0;
}

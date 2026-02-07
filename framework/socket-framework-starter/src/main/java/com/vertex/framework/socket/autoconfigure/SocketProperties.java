package com.vertex.framework.socket.autoconfigure;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Socket 框架配置属性
 */
@Data
@ConfigurationProperties(prefix = "vertex.socket")
public class SocketProperties {

    /** 服务端配置 */
    private Server server = new Server();

    /** 客户端配置 */
    private Client client = new Client();

    /** 连接池配置 */
    private Pool pool = new Pool();

    @Data
    public static class Server {
        /** 是否启用 WebSocket 服务端 */
        private boolean enabled = false;

        /** 监听端口 */
        private int port = 9090;

        /** WebSocket 路径 */
        private String path = "/ws";

        /** Boss 线程数 */
        private int bossThreads = 1;

        /** Worker 线程数 */
        private int workerThreads = 4;

        /** 最大帧大小（字节） */
        private int maxFrameSize = 65536;

        /** 心跳间隔（秒） */
        private int heartbeatIntervalSeconds = 60;
    }

    @Data
    public static class Client {
        /** 是否启用 WebSocket 客户端自动配置 */
        private boolean enabled = false;

        /** 心跳间隔（秒） */
        private int heartbeatInterval = 30;

        /** 最大丢失心跳次数 */
        private int maxMissedHeartbeats = 3;

        /** 重连配置 */
        private Reconnect reconnect = new Reconnect();
    }

    @Data
    public static class Reconnect {
        /** 是否启用自动重连 */
        private boolean enabled = true;

        /** 初始延迟（毫秒） */
        private long initialDelay = 1000;

        /** 最大延迟（毫秒） */
        private long maxDelay = 60000;

        /** 退避倍数 */
        private double multiplier = 2.0;

        /** 最大重试次数，-1 表示无限 */
        private int maxAttempts = -1;
    }

    @Data
    public static class Pool {
        /** 最大连接数 */
        private int maxTotal = 20;

        /** 最大空闲连接数 */
        private int maxIdle = 10;

        /** 最小空闲连接数 */
        private int minIdle = 2;

        /** 获取连接最大等待时间（毫秒） */
        private long maxWaitMs = 5000;
    }
}

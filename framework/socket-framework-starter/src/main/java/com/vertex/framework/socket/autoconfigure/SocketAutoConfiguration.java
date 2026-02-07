package com.vertex.framework.socket.autoconfigure;

import com.vertex.framework.socket.codec.JsonMessageCodec;
import com.vertex.framework.socket.codec.MessageCodec;
import com.vertex.framework.socket.event.SocketEventPublisher;
import com.vertex.framework.socket.heartbeat.DefaultHeartbeatStrategy;
import com.vertex.framework.socket.heartbeat.HeartbeatStrategy;
import com.vertex.framework.socket.reconnect.ExponentialBackoffPolicy;
import com.vertex.framework.socket.reconnect.ReconnectPolicy;
import com.vertex.framework.socket.server.SessionRegistry;
import com.vertex.framework.socket.server.WebSocketServer;
import com.vertex.framework.socket.server.WebSocketServerConfig;
import com.vertex.framework.socket.server.WebSocketServerHandler;
import com.vertex.framework.socket.subscription.SubscriptionManager;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Socket 框架自动配置
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(SocketProperties.class)
public class SocketAutoConfiguration {

    // ==================== 通用 Bean ====================

    @Bean
    @ConditionalOnMissingBean
    public MessageCodec messageCodec() {
        return new JsonMessageCodec();
    }

    @Bean
    @ConditionalOnMissingBean
    public HeartbeatStrategy heartbeatStrategy() {
        return new DefaultHeartbeatStrategy();
    }

    @Bean
    @ConditionalOnMissingBean
    public ReconnectPolicy reconnectPolicy(SocketProperties properties) {
        SocketProperties.Reconnect reconnect = properties.getClient().getReconnect();
        return new ExponentialBackoffPolicy(
                reconnect.getInitialDelay(),
                reconnect.getMaxDelay(),
                reconnect.getMultiplier(),
                reconnect.getMaxAttempts()
        );
    }

    @Bean
    @ConditionalOnMissingBean
    public SubscriptionManager subscriptionManager() {
        return new SubscriptionManager();
    }

    @Bean
    @ConditionalOnMissingBean
    public SocketEventPublisher socketEventPublisher(ApplicationEventPublisher eventPublisher) {
        return new SocketEventPublisher(eventPublisher);
    }

    // ==================== 服务端自动配置 ====================

    @Configuration
    @ConditionalOnProperty(prefix = "vertex.socket.server", name = "enabled", havingValue = "true")
    static class ServerAutoConfiguration {

        private WebSocketServer webSocketServer;

        @Bean
        @ConditionalOnMissingBean
        public SessionRegistry sessionRegistry() {
            return new SessionRegistry();
        }

        @Bean
        @ConditionalOnMissingBean
        public WebSocketServer webSocketServer(SocketProperties properties,
                                               @Autowired(required = false) WebSocketServerHandler.ServerMessageListener messageListener) {
            SocketProperties.Server serverProps = properties.getServer();
            WebSocketServerConfig config = WebSocketServerConfig.builder()
                    .port(serverProps.getPort())
                    .path(serverProps.getPath())
                    .bossThreads(serverProps.getBossThreads())
                    .workerThreads(serverProps.getWorkerThreads())
                    .maxFrameSize(serverProps.getMaxFrameSize())
                    .heartbeatIntervalSeconds(serverProps.getHeartbeatIntervalSeconds())
                    .build();

            webSocketServer = new WebSocketServer(config, messageListener);

            // 启动服务端
            try {
                webSocketServer.start();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("Failed to start WebSocket server", e);
                throw new RuntimeException("Failed to start WebSocket server", e);
            }

            return webSocketServer;
        }

        @PreDestroy
        public void stopServer() {
            if (webSocketServer != null) {
                webSocketServer.stop();
            }
        }
    }
}

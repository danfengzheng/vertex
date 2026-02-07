package com.vertex.framework.socket.exchange;

import com.vertex.framework.socket.client.WebSocketClient;
import com.vertex.framework.socket.client.WebSocketClientConfig;
import com.vertex.framework.socket.client.WebSocketClientHandler;
import com.vertex.framework.socket.core.SocketSession;
import com.vertex.framework.socket.heartbeat.HeartbeatStrategy;
import com.vertex.framework.socket.reconnect.ExponentialBackoffPolicy;
import com.vertex.framework.socket.subscription.SubscriptionListener;
import com.vertex.framework.socket.subscription.SubscriptionManager;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.util.Map;

/**
 * 交易所 WebSocket 客户端抽象类
 * <p>
 * 提供模板方法模式，子类只需实现：
 * - buildSubscribeMessage: 构建订阅请求消息
 * - buildUnsubscribeMessage: 构建取消订阅请求消息
 * - parseMessage: 解析交易所返回的消息，提取 topic 和 payload
 * - createHeartbeatStrategy: 创建交易所特定的心跳策略
 */
@Slf4j
public abstract class ExchangeWebSocketClient {

    @Getter
    private final ExchangeConfig exchangeConfig;

    @Getter
    private final SubscriptionManager subscriptionManager;

    private WebSocketClient webSocketClient;

    protected ExchangeWebSocketClient(ExchangeConfig exchangeConfig) {
        this.exchangeConfig = exchangeConfig;
        this.subscriptionManager = new SubscriptionManager();
    }

    /**
     * 连接交易所
     */
    public void connect() throws Exception {
        WebSocketClientConfig clientConfig = WebSocketClientConfig.builder()
                .uri(new URI(exchangeConfig.getWsUrl()))
                .ssl(exchangeConfig.getWsUrl().startsWith("wss"))
                .heartbeatIntervalSeconds(exchangeConfig.getHeartbeatIntervalSeconds())
                .heartbeatStrategy(createHeartbeatStrategy())
                .reconnectPolicy(new ExponentialBackoffPolicy())
                .autoReconnect(exchangeConfig.isAutoReconnect())
                .build();

        webSocketClient = new WebSocketClient(clientConfig, new WebSocketClientHandler.WebSocketMessageListener() {
            @Override
            public void onMessage(SocketSession session, String message) {
                handleMessage(message);
            }

            @Override
            public void onDisconnected(SocketSession session) {
                onConnectionLost();
            }

            @Override
            public void onError(SocketSession session, Throwable cause) {
                ExchangeWebSocketClient.this.onError(cause);
            }
        });

        webSocketClient.connect();
        onConnected();
    }

    /**
     * 断开连接
     */
    public void disconnect() {
        subscriptionManager.clear();
        if (webSocketClient != null) {
            webSocketClient.disconnect();
        }
    }

    /**
     * 订阅主题
     */
    public void subscribe(String topic, SubscriptionListener listener) {
        subscribe(topic, null, listener);
    }

    /**
     * 带参数订阅
     */
    public void subscribe(String topic, Map<String, String> params, SubscriptionListener listener) {
        subscriptionManager.subscribe(topic, params, listener);
        String message = buildSubscribeMessage(topic, params);
        sendMessage(message);
        log.info("[{}] Subscribed to: {}", exchangeConfig.getExchangeType().getCode(), topic);
    }

    /**
     * 取消订阅
     */
    public void unsubscribe(String topic) {
        subscriptionManager.unsubscribeAll(topic);
        String message = buildUnsubscribeMessage(topic);
        sendMessage(message);
        log.info("[{}] Unsubscribed from: {}", exchangeConfig.getExchangeType().getCode(), topic);
    }

    /**
     * 发送消息
     */
    protected void sendMessage(String message) {
        if (webSocketClient != null && webSocketClient.isConnected()) {
            webSocketClient.send(message);
        } else {
            log.warn("[{}] Cannot send message, not connected", exchangeConfig.getExchangeType().getCode());
        }
    }

    /**
     * 处理收到的消息
     */
    private void handleMessage(String rawMessage) {
        // 先检查是否为心跳响应
        HeartbeatStrategy strategy = createHeartbeatStrategy();
        if (strategy != null && strategy.isHeartbeatResponse(rawMessage)) {
            return;
        }

        try {
            ParsedMessage parsed = parseMessage(rawMessage);
            if (parsed != null && parsed.topic() != null) {
                subscriptionManager.dispatch(parsed.topic(), parsed.payload());
            }
            onMessage(rawMessage);
        } catch (Exception e) {
            log.error("[{}] Error handling message: {}", exchangeConfig.getExchangeType().getCode(), rawMessage, e);
        }
    }

    // ==================== 模板方法 ====================

    /**
     * 构建订阅消息（子类实现）
     */
    protected abstract String buildSubscribeMessage(String topic, Map<String, String> params);

    /**
     * 构建取消订阅消息（子类实现）
     */
    protected abstract String buildUnsubscribeMessage(String topic);

    /**
     * 解析交易所消息，提取 topic 和 payload（子类实现）
     */
    protected abstract ParsedMessage parseMessage(String rawMessage);

    /**
     * 创建交易所特定的心跳策略（子类实现）
     */
    protected abstract HeartbeatStrategy createHeartbeatStrategy();

    // ==================== 生命周期回调（子类可覆写） ====================

    protected void onConnected() {
        log.info("[{}] Connected to exchange", exchangeConfig.getExchangeType().getCode());
    }

    protected void onConnectionLost() {
        log.warn("[{}] Connection lost", exchangeConfig.getExchangeType().getCode());
    }

    protected void onMessage(String rawMessage) {
        // 子类可覆写做额外处理
    }

    protected void onError(Throwable cause) {
        log.error("[{}] Error occurred", exchangeConfig.getExchangeType().getCode(), cause);
    }

    /**
     * 解析后的消息
     */
    public record ParsedMessage(String topic, String payload) {
    }
}

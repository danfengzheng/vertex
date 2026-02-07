package com.vertex.service.quote.source.ws;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.vertex.framework.socket.exchange.ExchangeConfig;
import com.vertex.framework.socket.exchange.ExchangeType;
import com.vertex.framework.socket.exchange.ExchangeWebSocketClient;
import com.vertex.framework.socket.heartbeat.HeartbeatStrategy;
import com.vertex.model.entity.quote.KLine;
import com.vertex.model.entity.quote.KLineInterval;
import com.vertex.service.quote.converter.KLineConverter;
import com.vertex.service.quote.notify.CompositeNotifier;
import com.vertex.service.quote.source.QuoteDataSource;
import com.vertex.service.quote.store.KLineStore;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * OKX WebSocket 数据源
 * <p>
 * 通过继承 ExchangeWebSocketClient 接入 OKX WebSocket，
 * 订阅 K线数据后自动完成：转换 → 存储 → 通知 全流程。
 * <p>
 * OKX WS 订阅格式：
 * {"op":"subscribe","args":[{"channel":"candle1m","instId":"BTC-USDT"}]}
 */
@Slf4j
public class OkxWsDataSource extends ExchangeWebSocketClient implements QuoteDataSource {

    private final KLineConverter klineConverter;
    private final KLineStore klineStore;
    private final CompositeNotifier notifier;

    /** 记录已订阅的主题及其 interval 映射 */
    private final Map<String, KLineInterval> topicIntervalMap = new ConcurrentHashMap<>();
    /** 记录已订阅的主题及其统一 symbol 映射 */
    private final Map<String, String> topicSymbolMap = new ConcurrentHashMap<>();

    private volatile boolean connected = false;

    public OkxWsDataSource(ExchangeConfig config,
                           KLineConverter klineConverter,
                           KLineStore klineStore,
                           CompositeNotifier notifier) {
        super(config);
        this.klineConverter = klineConverter;
        this.klineStore = klineStore;
        this.notifier = notifier;
    }

    @Override
    public String exchangeCode() {
        return "okx";
    }

    @Override
    public void start() {
        try {
            connect();
        } catch (Exception e) {
            log.error("[OKX] Failed to start WebSocket data source", e);
            throw new RuntimeException("Failed to connect to OKX WebSocket", e);
        }
    }

    @Override
    public void stop() {
        disconnect();
        topicIntervalMap.clear();
        topicSymbolMap.clear();
        connected = false;
    }

    @Override
    public void subscribe(String symbol, KLineInterval interval) {
        String topic = buildTopic(symbol, interval);
        topicIntervalMap.put(topic, interval);
        topicSymbolMap.put(topic, symbol);

        // 注册订阅监听，回调中完成转换→存储→通知
        super.subscribe(topic, (t, payload) -> {
            try {
                KLine kline = klineConverter.convert(symbol, interval, payload);
                if (kline != null) {
                    klineStore.save(kline);
                    notifier.notifyKLine(kline);
                }
            } catch (Exception e) {
                log.error("[OKX] Error processing KLine for {}:{}", symbol, interval.getCode(), e);
            }
        });
    }

    @Override
    public void unsubscribe(String symbol, KLineInterval interval) {
        String topic = buildTopic(symbol, interval);
        topicIntervalMap.remove(topic);
        topicSymbolMap.remove(topic);
        super.unsubscribe(topic);
    }

    @Override
    public boolean isConnected() {
        return connected;
    }

    // ==================== ExchangeWebSocketClient 模板方法 ====================

    @Override
    protected String buildSubscribeMessage(String topic, Map<String, String> params) {
        // topic 格式: candle1m:BTC-USDT，解析出 channel 和 instId
        String[] parts = topic.split(":");
        String channel = parts[0];
        String instId = parts.length > 1 ? parts[1] : "";

        JSONObject arg = new JSONObject();
        arg.put("channel", channel);
        arg.put("instId", instId);

        JSONObject msg = new JSONObject();
        msg.put("op", "subscribe");
        msg.put("args", new JSONObject[]{arg});
        return msg.toJSONString();
    }

    @Override
    protected String buildUnsubscribeMessage(String topic) {
        String[] parts = topic.split(":");
        String channel = parts[0];
        String instId = parts.length > 1 ? parts[1] : "";

        JSONObject arg = new JSONObject();
        arg.put("channel", channel);
        arg.put("instId", instId);

        JSONObject msg = new JSONObject();
        msg.put("op", "unsubscribe");
        msg.put("args", new JSONObject[]{arg});
        return msg.toJSONString();
    }

    @Override
    protected ParsedMessage parseMessage(String rawMessage) {
        try {
            JSONObject json = JSON.parseObject(rawMessage);

            // OKX 数据推送格式: {"arg":{"channel":"candle1m","instId":"BTC-USDT"},"data":[[...]]}
            JSONObject arg = json.getJSONObject("arg");
            JSONArray data = json.getJSONArray("data");

            if (arg != null && data != null && !data.isEmpty()) {
                String channel = arg.getString("channel");
                String instId = arg.getString("instId");

                // 只处理 candle 开头的频道（K线数据）
                if (channel != null && channel.startsWith("candle")) {
                    String topic = channel + ":" + instId;
                    return new ParsedMessage(topic, rawMessage);
                }
            }
        } catch (Exception e) {
            log.debug("[OKX] Unrecognized message format: {}", rawMessage);
        }
        return null;
    }

    @Override
    protected HeartbeatStrategy createHeartbeatStrategy() {
        // OKX 使用 "ping" 文本心跳
        return new OkxHeartbeatStrategy();
    }

    @Override
    protected void onConnected() {
        super.onConnected();
        connected = true;
        log.info("[OKX] WebSocket data source connected");
    }

    @Override
    protected void onConnectionLost() {
        super.onConnectionLost();
        connected = false;
        log.warn("[OKX] WebSocket data source connection lost");
    }

    // ==================== 辅助方法 ====================

    /**
     * 构建 OKX WebSocket 订阅 topic
     * 格式：candle1m:BTC-USDT
     */
    private String buildTopic(String symbol, KLineInterval interval) {
        String channel = "candle" + interval.getCode();
        return channel + ":" + symbol;
    }

    /**
     * 创建默认的 OKX ExchangeConfig
     */
    public static ExchangeConfig defaultConfig() {
        return ExchangeConfig.builder()
                .exchangeType(ExchangeType.OKX)
                .wsUrl("wss://ws.okx.com:8443/ws/v5/public")
                .apiUrl("https://www.okx.com")
                .heartbeatIntervalSeconds(25)
                .autoReconnect(true)
                .build();
    }

    /**
     * OKX 心跳策略
     * OKX WebSocket 使用 "ping" 文本消息作为心跳
     */
    private static class OkxHeartbeatStrategy implements HeartbeatStrategy {

        @Override
        public void sendHeartbeat(Channel channel) {
            if (channel.isActive()) {
                channel.writeAndFlush(new TextWebSocketFrame("ping"));
            }
        }

        @Override
        public boolean isHeartbeatResponse(String message) {
            return "pong".equals(message);
        }

        @Override
        public void handleHeartbeatResponse(Channel channel, String message) {
            // no-op
        }
    }
}

package com.vertex.service.quote.source.ws;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.vertex.framework.socket.exchange.ExchangeConfig;
import com.vertex.framework.socket.exchange.ExchangeType;
import com.vertex.framework.socket.exchange.ExchangeWebSocketClient;
import com.vertex.framework.socket.heartbeat.DefaultHeartbeatStrategy;
import com.vertex.framework.socket.heartbeat.HeartbeatStrategy;
import com.vertex.model.entity.quote.KLine;
import com.vertex.model.entity.quote.KLineInterval;
import com.vertex.service.quote.converter.KLineConverter;
import com.vertex.service.quote.notify.CompositeNotifier;
import com.vertex.service.quote.source.QuoteDataSource;
import com.vertex.service.quote.store.KLineStore;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 币安 WebSocket 数据源
 * <p>
 * 通过继承 ExchangeWebSocketClient 接入币安 WebSocket，
 * 订阅 K线数据后自动完成：转换 → 存储 → 通知 全流程。
 * <p>
 * 币安 WS 订阅格式：
 * {"method":"SUBSCRIBE","params":["btcusdt@kline_1m"],"id":1}
 */
@Slf4j
public class BinanceWsDataSource extends ExchangeWebSocketClient implements QuoteDataSource {

    private final KLineConverter klineConverter;
    private final KLineStore klineStore;
    private final CompositeNotifier notifier;

    /** 记录已订阅的主题及其 interval 映射 */
    private final Map<String, KLineInterval> topicIntervalMap = new ConcurrentHashMap<>();
    /** 记录已订阅的主题及其统一 symbol 映射 */
    private final Map<String, String> topicSymbolMap = new ConcurrentHashMap<>();

    private volatile boolean connected = false;

    public BinanceWsDataSource(ExchangeConfig config,
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
        return "binance";
    }

    @Override
    public void start() {
        try {
            connect();
        } catch (Exception e) {
            log.error("[Binance] Failed to start WebSocket data source", e);
            throw new RuntimeException("Failed to connect to Binance WebSocket", e);
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
                log.error("[Binance] Error processing KLine for {}:{}", symbol, interval.getCode(), e);
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
        JSONObject msg = new JSONObject();
        msg.put("method", "SUBSCRIBE");
        msg.put("params", new String[]{topic});
        msg.put("id", System.currentTimeMillis());
        return msg.toJSONString();
    }

    @Override
    protected String buildUnsubscribeMessage(String topic) {
        JSONObject msg = new JSONObject();
        msg.put("method", "UNSUBSCRIBE");
        msg.put("params", new String[]{topic});
        msg.put("id", System.currentTimeMillis());
        return msg.toJSONString();
    }

    @Override
    protected ParsedMessage parseMessage(String rawMessage) {
        try {
            JSONObject json = JSON.parseObject(rawMessage);
            // 币安 kline 推送格式: {"e":"kline","s":"BTCUSDT","k":{...}}
            String eventType = json.getString("e");
            if ("kline".equals(eventType)) {
                JSONObject k = json.getJSONObject("k");
                if (k != null) {
                    String binanceSymbol = json.getString("s").toLowerCase();
                    String interval = k.getString("i");
                    String topic = binanceSymbol + "@kline_" + interval;
                    return new ParsedMessage(topic, rawMessage);
                }
            }
        } catch (Exception e) {
            log.debug("[Binance] Unrecognized message format: {}", rawMessage);
        }
        return null;
    }

    @Override
    protected HeartbeatStrategy createHeartbeatStrategy() {
        return new DefaultHeartbeatStrategy();
    }

    @Override
    protected void onConnected() {
        super.onConnected();
        connected = true;
        log.info("[Binance] WebSocket data source connected");
    }

    @Override
    protected void onConnectionLost() {
        super.onConnectionLost();
        connected = false;
        log.warn("[Binance] WebSocket data source connection lost");
    }

    // ==================== 辅助方法 ====================

    /**
     * 构建币安 WebSocket 订阅 topic
     * 格式：btcusdt@kline_1m
     */
    private String buildTopic(String symbol, KLineInterval interval) {
        // 统一格式 BTC-USDT → btcusdt
        String binanceSymbol = symbol.replace("-", "").toLowerCase();
        return binanceSymbol + "@kline_" + interval.getCode();
    }

    /**
     * 创建默认的币安 ExchangeConfig
     */
    public static ExchangeConfig defaultConfig() {
        return ExchangeConfig.builder()
                .exchangeType(ExchangeType.BINANCE)
                .wsUrl("wss://stream.binance.com:9443/ws")
                .apiUrl("https://api.binance.com")
                .heartbeatIntervalSeconds(20)
                .autoReconnect(true)
                .build();
    }
}

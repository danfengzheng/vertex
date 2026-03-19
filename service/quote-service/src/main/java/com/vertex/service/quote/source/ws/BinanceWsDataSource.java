package com.vertex.service.quote.source.ws;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.vertex.framework.socket.exchange.ExchangeConfig;
import com.vertex.framework.socket.exchange.ExchangeType;
import com.vertex.framework.socket.exchange.ExchangeWebSocketClient;
import com.vertex.framework.socket.heartbeat.HeartbeatStrategy;
import com.vertex.model.entity.quote.KLine;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import com.vertex.model.entity.quote.KLineInterval;
import com.vertex.service.quote.aggregator.InMemoryKLineAggregator;
import com.vertex.service.quote.converter.KLineConverter;
import com.vertex.service.quote.handler.KLineFlushOnNextHandler;
import com.vertex.service.quote.notify.CompositeNotifier;
import com.vertex.service.quote.source.QuoteDataSource;
import com.vertex.service.quote.store.KLineStore;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 币安 WebSocket 数据源
 * <p>
 * 通过继承 ExchangeWebSocketClient 接入币安 WebSocket，
 * 订阅 K线数据后自动完成：转换 → 存储 → 通知 全流程。
 * <p>
 * 币安 WS 订阅格式：
 * {"method":"SUBSCRIBE","params":["btcusdt@kline_1m"],"id":1}
 * 当注入 InMemoryKLineAggregator 时：日 K 以下改为订阅 trade 流，由聚合器本地聚合成 K 线；日 K 及以上仍订阅 kline。
 */
@Slf4j
public class BinanceWsDataSource extends ExchangeWebSocketClient implements QuoteDataSource {

    private final KLineConverter klineConverter;
    private final KLineStore klineStore;
    private final CompositeNotifier notifier;
    private final KLineFlushOnNextHandler klineFlushOnNextHandler;
    /** 可选：注入后日 K 以下用 trade 流 + 内存聚合 */
    private final InMemoryKLineAggregator aggregator;

    /** 记录已订阅的主题及其 interval 映射（kline） */
    private final Map<String, KLineInterval> topicIntervalMap = new ConcurrentHashMap<>();
    /** 记录已订阅的主题及其统一 symbol 映射（kline） */
    private final Map<String, String> topicSymbolMap = new ConcurrentHashMap<>();
    /** 日 K 以下：已订阅 trade 的 symbol -> 该 symbol 下订阅的 interval 集合 */
    private final Map<String, Set<KLineInterval>> tradeSymbolIntervals = new ConcurrentHashMap<>();

    private volatile boolean connected = false;

    /** 防抖：已连接时多次 subscribe 只发一次批量订阅，避免 5 策略×3 标的 连续发 15 条触发限流 */
    private final ScheduledExecutorService batchSchedule = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "binance-batch-subscribe");
        t.setDaemon(true);
        return t;
    });
    private final AtomicReference<ScheduledFuture<?>> pendingBatchRef = new AtomicReference<>();
    private static final long BATCH_DEBOUNCE_MS = 80;

    public BinanceWsDataSource(ExchangeConfig config,
                               KLineConverter klineConverter,
                               KLineStore klineStore,
                               CompositeNotifier notifier,
                               KLineFlushOnNextHandler klineFlushOnNextHandler) {
        this(config, klineConverter, klineStore, notifier, klineFlushOnNextHandler, null);
    }

    public BinanceWsDataSource(ExchangeConfig config,
                               KLineConverter klineConverter,
                               KLineStore klineStore,
                               CompositeNotifier notifier,
                               KLineFlushOnNextHandler klineFlushOnNextHandler,
                               InMemoryKLineAggregator aggregator) {
        super(config);
        this.klineConverter = klineConverter;
        this.klineStore = klineStore;
        this.notifier = notifier;
        this.klineFlushOnNextHandler = klineFlushOnNextHandler;
        this.aggregator = aggregator;
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
        tradeSymbolIntervals.clear();
        connected = false;
    }

    @Override
    public void subscribe(String symbol, KLineInterval interval) {
        if (aggregator != null && InMemoryKLineAggregator.isIntraday(interval)) {
            subscribeTrade(symbol, interval);
            return;
        }
        log.info("订阅Socket Kline {} , {}",symbol,interval.getCode());
        subscribeKline(symbol, interval);
    }

    @Override
    public void unsubscribe(String symbol, KLineInterval interval) {
        if (aggregator != null && InMemoryKLineAggregator.isIntraday(interval)) {
            unsubscribeTrade(symbol, interval);
            return;
        }
        String topic = buildKlineTopic(symbol, interval);
        topicIntervalMap.remove(topic);
        topicSymbolMap.remove(topic);
        super.unsubscribe(topic);
    }

    private void subscribeTrade(String symbol, KLineInterval interval) {
        String binanceSymbol = symbol.replace("-", "").toLowerCase();
        String topic = buildTradeTopic(binanceSymbol);
        tradeSymbolIntervals.computeIfAbsent(symbol, k -> ConcurrentHashMap.newKeySet()).add(interval);
        if (tradeSymbolIntervals.get(symbol).size() == 1) {
            getSubscriptionManager().subscribe(topic, null, (t, payload) -> {
                try {
                    JSONObject j = JSON.parseObject(payload);
                    BigDecimal p = j.getBigDecimal("p");
                    BigDecimal q = j.getBigDecimal("q");
                    long timeMs = j.getLongValue("T");
                    aggregator.onTrade(exchangeCode(), symbol, p, q, timeMs);
                } catch (Exception e) {
                    log.error("[Binance] Error processing trade for {}: {}", symbol, payload, e);
                }
            });
            if (connected) {
                scheduleBatchSubscribe();
            }
        }
    }

    private void unsubscribeTrade(String symbol, KLineInterval interval) {
        Set<KLineInterval> intervals = tradeSymbolIntervals.get(symbol);
        if (intervals == null) return;
        intervals.remove(interval);
        if (intervals.isEmpty()) {
            tradeSymbolIntervals.remove(symbol);
            String binanceSymbol = symbol.replace("-", "").toLowerCase();
            super.unsubscribe(buildTradeTopic(binanceSymbol));
        }
    }

    private void subscribeKline(String symbol, KLineInterval interval) {
        String topic = buildKlineTopic(symbol, interval);
        boolean isNewTopic = !topicIntervalMap.containsKey(topic);
        topicIntervalMap.put(topic, interval);
        topicSymbolMap.put(topic, symbol);
        // 仅首次注册 listener，防止策略多次 enable/disable 后同一 topic 积累重复监听器
        if (isNewTopic) {
            getSubscriptionManager().subscribe(topic, null, (t, payload) -> {
                try {
                    KLine kline = klineConverter.convert(symbol, interval, payload);
                    if (kline != null) {
                        klineFlushOnNextHandler.submit(kline);
                    }
                } catch (Exception e) {
                    log.error("[Binance] Error processing KLine for {}:{}", symbol, interval.getCode(), e);
                }
            });
        }
        if (connected) {
            scheduleBatchSubscribe();
        }
    }

    /** 已连接时新增订阅防抖：多次 subscribe 在 BATCH_DEBOUNCE_MS 内只发一次批量，避免触发限流 */
    private void scheduleBatchSubscribe() {
        ScheduledFuture<?> prev = pendingBatchRef.getAndSet(batchSchedule.schedule(() -> {
            pendingBatchRef.set(null);
            sendBatchSubscribe();
        }, BATCH_DEBOUNCE_MS, TimeUnit.MILLISECONDS));
        if (prev != null) {
            prev.cancel(false);
        }
    }

    @Override
    public boolean isConnected() {
        return connected;
    }

    /**
     * 返回逻辑订阅列表，每条 (symbol, interval) 可单独取消。
     * 日 K 以下多条可能对应同一 trade 流，取消某 interval 仅移除该周期，最后一个移除时才退订 WS。
     */
    @Override
    public List<Map<String, String>> getSubscriptions() {
        List<Map<String, String>> result = new ArrayList<>();
        // ≥1D：kline 订阅，按 topic 记录
        topicIntervalMap.forEach((topic, interval) -> {
            String symbol = topicSymbolMap.get(topic);
            if (symbol != null) {
                result.add(Map.of("symbol", symbol, "interval", interval.name()));
            }
        });
        // 日 K 以下：trade 流，按 symbol -> intervals 展开为 (symbol, interval)
        tradeSymbolIntervals.forEach((symbol, intervals) -> {
            for (KLineInterval interval : intervals) {
                result.add(Map.of("symbol", symbol, "interval", interval.name()));
            }
        });
        return result;
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
            String eventType = json.getString("e");
            if ("trade".equals(eventType)) {
                String s = json.getString("s");
                if (s != null) {
                    String topic = s.toLowerCase() + "@trade";
                    return new ParsedMessage(topic, rawMessage);
                }
            }
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
        return new BinanceHeartbeatStrategy();
    }

    /**
     * 币安心跳策略
     * <p>
     * 币安 WebSocket 由服务端主动发送 Ping Frame，客户端需要回复 Pong Frame。
     * 客户端侧的心跳只需发送 Pong Frame 保持连接活跃，不应主动发 Ping Frame。
     */
    private static class BinanceHeartbeatStrategy implements HeartbeatStrategy {

        @Override
        public void sendHeartbeat(Channel channel) {
            if (channel.isActive()) {
                channel.writeAndFlush(new PongWebSocketFrame());
                log.debug("[Binance] Sent unsolicited pong to keep alive: {}", channel.remoteAddress());
            }
        }

        @Override
        public boolean isHeartbeatResponse(String message) {
            return false;
        }

        @Override
        public void handleHeartbeatResponse(Channel channel, String message) {
            // no-op
        }
    }

    @Override
    protected void onConnectionReady() {
        super.onConnectionReady();
        connected = true;
        log.info("[Binance] WebSocket data source connected");
        resubscribeAll();
    }

    @Override
    protected void onConnectionLost() {
        super.onConnectionLost();
        connected = false;
        log.warn("[Binance] WebSocket data source connection lost");
    }

    /** 重连后按当前订阅列表批量发送 SUBSCRIBE（一次请求多个 stream，避免多条消息触发限流断线） */
    private void resubscribeAll() {
        sendBatchSubscribe();
    }

    /** 单次请求最多订阅的 stream 数，避免超过交易所限制 */
    private static final int BATCH_SUBSCRIBE_LIMIT = 200;

    /**
     * 收集当前所有 topic，按批发送 SUBSCRIBE（params 为数组），只发一条或少量请求。
     * 币安限制约 5 条/秒，多策略启动时逐条发送易触发限流导致断线。
     */
    private void sendBatchSubscribe() {
        List<String> topics = new ArrayList<>(topicIntervalMap.keySet());
        for (String symbol : tradeSymbolIntervals.keySet()) {
            String topic = buildTradeTopic(symbol.replace("-", "").toLowerCase());
            if (!topics.contains(topic)) {
                topics.add(topic);
            }
        }
        if (topics.isEmpty()) {
            return;
        }
        int batchCount = 0;
        for (int i = 0; i < topics.size(); i += BATCH_SUBSCRIBE_LIMIT) {
            int to = Math.min(i + BATCH_SUBSCRIBE_LIMIT, topics.size());
            List<String> batch = topics.subList(i, to);
            sendMessage(buildBatchSubscribeMessage(batch));
            batchCount++;
        }
        log.info("[Binance] Batch subscribed {} stream(s) in {} request(s)", topics.size(), batchCount);
    }

    /** 构建批量订阅消息：params 为 stream 名称数组，单次最多 BATCH_SUBSCRIBE_LIMIT 个 */
    private String buildBatchSubscribeMessage(List<String> topics) {
        JSONObject msg = new JSONObject();
        msg.put("method", "SUBSCRIBE");
        msg.put("params", topics);
        msg.put("id", System.currentTimeMillis());
        return msg.toJSONString();
    }

    // ==================== 辅助方法 ====================

    private String buildTradeTopic(String binanceSymbol) {
        return binanceSymbol + "@trade";
    }

    private String buildKlineTopic(String symbol, KLineInterval interval) {
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

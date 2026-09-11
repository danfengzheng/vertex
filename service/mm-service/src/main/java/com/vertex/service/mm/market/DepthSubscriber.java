package com.vertex.service.mm.market;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.vertex.service.mm.config.MmProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 币安 partial book depth 订阅（10 档，100ms 频率）。
 * <p>
 * WS URL: {@code /ws/<symbol>@depth10@100ms}
 * 事件量 10 events/sec，比 aggTrade 少很多，可长期订阅。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DepthSubscriber {

    private final MmProperties properties;
    private final OrderBookCache cache;

    private final ConcurrentMap<String, DepthContext> subscriptions = new ConcurrentHashMap<>();

    private final OkHttpClient wsClient = new OkHttpClient.Builder()
            .pingInterval(60, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .build();

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1,
            r -> { Thread t = new Thread(r, "mm-depth-scheduler"); t.setDaemon(true); return t; });

    /** 60s 无消息判定为僵尸连接，强制重连。depth10@100ms 正常每 100ms 一条，静默 60s 极不正常 */
    private static final long WATCHDOG_STALE_MS = 60_000L;

    @jakarta.annotation.PostConstruct
    void startWatchdog() {
        scheduler.scheduleWithFixedDelay(this::checkStale, 30, 30, TimeUnit.SECONDS);
    }

    private void checkStale() {
        long now = System.currentTimeMillis();
        for (var e : subscriptions.entrySet()) {
            DepthContext ctx = e.getValue();
            long last = ctx.lastMessageAtMs.get();
            if (last == 0) continue;   // 尚未收到过消息，跳过
            if (now - last > WATCHDOG_STALE_MS) {
                log.warn("[Depth] symbol={} STALE ({} ms since last message) → force reconnect",
                        ctx.symbol, now - last);
                ctx.lastMessageAtMs.set(now);   // 重置避免连续触发
                if (ctx.webSocket != null) {
                    try { ctx.webSocket.close(1001, "watchdog stale"); } catch (Exception ignored) {}
                }
                scheduleReconnect(ctx);
            }
        }
    }

    public synchronized void subscribe(String symbol) {
        String upper = symbol.toUpperCase();
        DepthContext ctx = subscriptions.get(upper);
        if (ctx != null) {
            ctx.refCount.incrementAndGet();
            return;
        }
        ctx = new DepthContext(upper);
        ctx.refCount.set(1);
        subscriptions.put(upper, ctx);
        connect(ctx);
    }

    public synchronized void unsubscribe(String symbol) {
        String upper = symbol.toUpperCase();
        DepthContext ctx = subscriptions.get(upper);
        if (ctx == null) return;
        if (ctx.refCount.decrementAndGet() > 0) return;   // 还有其他策略订阅
        subscriptions.remove(upper);
        if (ctx.webSocket != null) {
            try { ctx.webSocket.close(1000, "unsubscribe"); } catch (Exception ignored) {}
        }
        log.info("[Depth] symbol={} fully unsubscribed", upper);
    }

    private void connect(DepthContext ctx) {
        try {
            String wsUrl = properties.getBinancePublicWsBase() + "/"
                    + ctx.symbol.toLowerCase() + "@depth10@100ms";
            Request req = new Request.Builder().url(wsUrl).build();
            ctx.webSocket = wsClient.newWebSocket(req, new DepthWsListener(ctx));
            log.info("[Depth] symbol={} connecting: {}", ctx.symbol, wsUrl);
        } catch (Exception ex) {
            log.error("[Depth] symbol={} connect error: {}", ctx.symbol, ex.getMessage());
            scheduleReconnect(ctx);
        }
    }

    private void scheduleReconnect(DepthContext ctx) {
        int attempt = ctx.reconnectAttempts.incrementAndGet();
        long waitSec = Math.min(properties.getWsReconnectMaxSec(),
                properties.getWsReconnectInitialSec() * (1L << Math.min(6, attempt)));
        log.info("[Depth] symbol={} reconnect in {}s", ctx.symbol, waitSec);
        scheduler.schedule(() -> connect(ctx), waitSec, TimeUnit.SECONDS);
    }

    private static class DepthContext {
        final String symbol;
        volatile WebSocket webSocket;
        final java.util.concurrent.atomic.AtomicInteger reconnectAttempts =
                new java.util.concurrent.atomic.AtomicInteger(0);
        final java.util.concurrent.atomic.AtomicInteger refCount =
                new java.util.concurrent.atomic.AtomicInteger(0);
        final java.util.concurrent.atomic.AtomicLong lastMessageAtMs =
                new java.util.concurrent.atomic.AtomicLong(0);
        DepthContext(String symbol) { this.symbol = symbol; }
    }

    private class DepthWsListener extends WebSocketListener {
        private final DepthContext ctx;
        DepthWsListener(DepthContext ctx) { this.ctx = ctx; }

        @Override
        public void onOpen(WebSocket ws, Response resp) {
            ctx.reconnectAttempts.set(0);
            log.info("[Depth] symbol={} connected", ctx.symbol);
        }

        @Override
        public void onMessage(WebSocket ws, String text) {
            ctx.lastMessageAtMs.set(System.currentTimeMillis());
            try {
                JSONObject j = JSON.parseObject(text);
                // {"lastUpdateId":..,"E":..,"T":..,"s":"ETHUSDC","bids":[["4000.0","0.5"],...],"asks":[[..]]}
                List<OrderBookLevel> bids = parseLevels(j.getJSONArray("bids"));
                List<OrderBookLevel> asks = parseLevels(j.getJSONArray("asks"));
                OrderBookSnapshot snap = OrderBookSnapshot.builder()
                        .symbol(ctx.symbol)
                        .bids(bids)
                        .asks(asks)
                        .updateTimeMs(j.containsKey("T") ? j.getLongValue("T") : System.currentTimeMillis())
                        .build();
                cache.update(snap);
            } catch (Exception ex) {
                log.warn("[Depth] symbol={} parse error: {}", ctx.symbol, ex.getMessage());
            }
        }

        @Override
        public void onClosed(WebSocket ws, int code, String reason) {
            log.info("[Depth] symbol={} closed: {} {}", ctx.symbol, code, reason);
            if (subscriptions.containsKey(ctx.symbol)) scheduleReconnect(ctx);
        }

        @Override
        public void onFailure(WebSocket ws, Throwable t, Response resp) {
            log.warn("[Depth] symbol={} failure: {}", ctx.symbol, t.getMessage());
            if (subscriptions.containsKey(ctx.symbol)) scheduleReconnect(ctx);
        }

        private List<OrderBookLevel> parseLevels(JSONArray arr) {
            List<OrderBookLevel> out = new ArrayList<>();
            if (arr == null) return out;
            for (int i = 0; i < arr.size(); i++) {
                JSONArray row = arr.getJSONArray(i);
                if (row == null || row.size() < 2) continue;
                out.add(new OrderBookLevel(new BigDecimal(row.getString(0)), new BigDecimal(row.getString(1))));
            }
            return out;
        }
    }
}

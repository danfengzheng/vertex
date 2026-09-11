package com.vertex.service.mm.market;

import com.alibaba.fastjson2.JSON;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 币安公有 book ticker WebSocket 订阅。
 * <p>
 * 每个 symbol 建一个独立连接（{@code wss://.../ws/ethusdc@bookTicker}）。
 * 收到事件立刻更新 {@link MarketQuoteCache}。
 * </p>
 * <p>
 * 注意：**不复用** quote-service 的 K 线 WS，独立故障隔离。
 * 事件量 ~200/sec，独立 event loop 处理。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BookTickerSubscriber {

    private final MmProperties properties;
    private final MarketQuoteCache cache;

    /** symbol → 连接上下文（每个 symbol 一条连接）*/
    private final ConcurrentMap<String, TickerContext> subscriptions = new ConcurrentHashMap<>();

    private final OkHttpClient wsClient = new OkHttpClient.Builder()
            .pingInterval(60, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .build();

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1,
            r -> {
                Thread t = new Thread(r, "mm-bookticker-scheduler");
                t.setDaemon(true);
                return t;
            });

    /** bookTicker 主流每秒数十条，60s 静默视为僵尸 */
    private static final long WATCHDOG_STALE_MS = 60_000L;

    @jakarta.annotation.PostConstruct
    void startWatchdog() {
        scheduler.scheduleWithFixedDelay(this::checkStale, 30, 30, TimeUnit.SECONDS);
    }

    private void checkStale() {
        long now = System.currentTimeMillis();
        for (var e : subscriptions.entrySet()) {
            TickerContext ctx = e.getValue();
            long last = ctx.lastMessageAtMs.get();
            if (last == 0) continue;
            if (now - last > WATCHDOG_STALE_MS) {
                log.warn("[BookTicker] symbol={} STALE ({} ms) → force reconnect", ctx.symbol, now - last);
                ctx.lastMessageAtMs.set(now);
                if (ctx.webSocket != null) {
                    try { ctx.webSocket.close(1001, "watchdog stale"); } catch (Exception ignored) {}
                }
                scheduleReconnect(ctx);
            }
        }
    }

    /**
     * 订阅某 symbol。重复调用幂等。
     */
    public synchronized void subscribe(String symbol) {
        String upper = symbol.toUpperCase();
        TickerContext ctx = subscriptions.get(upper);
        if (ctx != null) {
            ctx.refCount.incrementAndGet();
            return;
        }
        ctx = new TickerContext(upper);
        ctx.refCount.set(1);
        subscriptions.put(upper, ctx);
        connect(ctx);
    }

    /**
     * 取消订阅（引用计数：仅当所有订阅者都退订才关闭连接）。
     */
    public synchronized void unsubscribe(String symbol) {
        String upper = symbol.toUpperCase();
        TickerContext ctx = subscriptions.get(upper);
        if (ctx == null) return;
        if (ctx.refCount.decrementAndGet() > 0) return;
        subscriptions.remove(upper);
        if (ctx.webSocket != null) {
            try { ctx.webSocket.close(1000, "unsubscribe"); } catch (Exception ignored) {}
        }
        log.info("[BookTicker] symbol={} fully unsubscribed", upper);
    }

    private void connect(TickerContext ctx) {
        try {
            String wsUrl = properties.getBinancePublicWsBase() + "/"
                    + ctx.symbol.toLowerCase() + "@bookTicker";
            Request req = new Request.Builder().url(wsUrl).build();
            ctx.webSocket = wsClient.newWebSocket(req, new BookTickerWsListener(ctx));
            log.info("[BookTicker] symbol={} connecting: {}", ctx.symbol, wsUrl);
        } catch (Exception ex) {
            log.error("[BookTicker] symbol={} connect error: {}", ctx.symbol, ex.getMessage());
            scheduleReconnect(ctx);
        }
    }

    private void scheduleReconnect(TickerContext ctx) {
        int attempt = ctx.reconnectAttempts.incrementAndGet();
        long waitSec = Math.min(properties.getWsReconnectMaxSec(),
                properties.getWsReconnectInitialSec() * (1L << Math.min(6, attempt)));
        log.info("[BookTicker] symbol={} reconnect in {}s (attempt {})", ctx.symbol, waitSec, attempt);
        scheduler.schedule(() -> connect(ctx), waitSec, TimeUnit.SECONDS);
    }

    private static class TickerContext {
        final String symbol;
        volatile WebSocket webSocket;
        final java.util.concurrent.atomic.AtomicInteger reconnectAttempts =
                new java.util.concurrent.atomic.AtomicInteger(0);
        final java.util.concurrent.atomic.AtomicInteger refCount =
                new java.util.concurrent.atomic.AtomicInteger(0);
        final java.util.concurrent.atomic.AtomicLong lastMessageAtMs =
                new java.util.concurrent.atomic.AtomicLong(0);

        TickerContext(String symbol) {
            this.symbol = symbol;
        }
    }

    private class BookTickerWsListener extends WebSocketListener {
        private final TickerContext ctx;

        BookTickerWsListener(TickerContext ctx) {
            this.ctx = ctx;
        }

        @Override
        public void onOpen(WebSocket webSocket, Response response) {
            ctx.reconnectAttempts.set(0);
            log.info("[BookTicker] symbol={} connected", ctx.symbol);
        }

        @Override
        public void onMessage(WebSocket webSocket, String text) {
            ctx.lastMessageAtMs.set(System.currentTimeMillis());
            try {
                JSONObject json = JSON.parseObject(text);
                // {"u":123,"s":"ETHUSDC","b":"...","B":"...","a":"...","A":"...","E":..,"T":..}
                String sym = json.getString("s");
                MarketQuote q = MarketQuote.builder()
                        .symbol(sym == null ? ctx.symbol : sym)
                        .bestBid(new BigDecimal(json.getString("b")))
                        .bestBidQty(new BigDecimal(json.getString("B")))
                        .bestAsk(new BigDecimal(json.getString("a")))
                        .bestAskQty(new BigDecimal(json.getString("A")))
                        .updateTimeMs(json.containsKey("T") ? json.getLongValue("T") : System.currentTimeMillis())
                        .build();
                cache.update(q);
            } catch (Exception ex) {
                log.warn("[BookTicker] symbol={} parse error: {}", ctx.symbol, ex.getMessage());
            }
        }

        @Override
        public void onClosed(WebSocket webSocket, int code, String reason) {
            log.info("[BookTicker] symbol={} closed: code={}, reason={}", ctx.symbol, code, reason);
            if (subscriptions.containsKey(ctx.symbol)) scheduleReconnect(ctx);
        }

        @Override
        public void onFailure(WebSocket webSocket, Throwable t, Response response) {
            log.warn("[BookTicker] symbol={} failure: {}", ctx.symbol, t.getMessage());
            if (subscriptions.containsKey(ctx.symbol)) scheduleReconnect(ctx);
        }
    }
}

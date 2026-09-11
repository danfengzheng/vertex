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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 公有 aggTrade 订阅 —— Paper 模式下 PaperFillSimulator 消费此流判定成交。
 * <p>
 * 每 symbol 一条连接，事件量 100-1000/sec（ETH/BTC 主流）。
 * Live 模式下不需要订阅（除非做订单流分析）。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AggTradeSubscriber {

    private final MmProperties properties;

    /** symbol → 连接 */
    private final ConcurrentMap<String, TradeContext> subscriptions = new ConcurrentHashMap<>();

    /** 所有 listener，事件到达时广播 */
    private final java.util.List<AggTradeListener> listeners = new CopyOnWriteArrayList<>();

    private final OkHttpClient wsClient = new OkHttpClient.Builder()
            .pingInterval(60, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .build();

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1,
            r -> { Thread t = new Thread(r, "mm-aggtrade-scheduler"); t.setDaemon(true); return t; });

    /** 主流品种 aggTrade 每秒数十到数百条，静默 120s 视为僵尸（放宽 tolerance vs depth）*/
    private static final long WATCHDOG_STALE_MS = 120_000L;

    @jakarta.annotation.PostConstruct
    void startWatchdog() {
        scheduler.scheduleWithFixedDelay(this::checkStale, 30, 30, TimeUnit.SECONDS);
    }

    private void checkStale() {
        long now = System.currentTimeMillis();
        for (var e : subscriptions.entrySet()) {
            TradeContext ctx = e.getValue();
            long last = ctx.lastMessageAtMs.get();
            if (last == 0) continue;
            if (now - last > WATCHDOG_STALE_MS) {
                log.warn("[AggTrade] symbol={} STALE ({} ms) → force reconnect", ctx.symbol, now - last);
                ctx.lastMessageAtMs.set(now);
                if (ctx.webSocket != null) {
                    try { ctx.webSocket.close(1001, "watchdog stale"); } catch (Exception ignored) {}
                }
                scheduleReconnect(ctx);
            }
        }
    }

    public void addListener(AggTradeListener l) { listeners.add(l); }
    public void removeListener(AggTradeListener l) { listeners.remove(l); }

    public synchronized void subscribe(String symbol) {
        String upper = symbol.toUpperCase();
        TradeContext ctx = subscriptions.get(upper);
        if (ctx != null) {
            ctx.refCount.incrementAndGet();
            return;
        }
        ctx = new TradeContext(upper);
        ctx.refCount.set(1);
        subscriptions.put(upper, ctx);
        connect(ctx);
    }

    public synchronized void unsubscribe(String symbol) {
        String upper = symbol.toUpperCase();
        TradeContext ctx = subscriptions.get(upper);
        if (ctx == null) return;
        if (ctx.refCount.decrementAndGet() > 0) return;
        subscriptions.remove(upper);
        if (ctx.webSocket != null) {
            try { ctx.webSocket.close(1000, "unsubscribe"); } catch (Exception ignored) {}
        }
        log.info("[AggTrade] symbol={} fully unsubscribed", upper);
    }

    private void connect(TradeContext ctx) {
        try {
            String wsUrl = properties.getBinancePublicWsBase() + "/"
                    + ctx.symbol.toLowerCase() + "@aggTrade";
            Request req = new Request.Builder().url(wsUrl).build();
            ctx.webSocket = wsClient.newWebSocket(req, new AggTradeWsListener(ctx));
            log.info("[AggTrade] symbol={} connecting: {}", ctx.symbol, wsUrl);
        } catch (Exception ex) {
            log.error("[AggTrade] symbol={} connect error: {}", ctx.symbol, ex.getMessage());
            scheduleReconnect(ctx);
        }
    }

    private void scheduleReconnect(TradeContext ctx) {
        int attempt = ctx.reconnectAttempts.incrementAndGet();
        long waitSec = Math.min(properties.getWsReconnectMaxSec(),
                properties.getWsReconnectInitialSec() * (1L << Math.min(6, attempt)));
        scheduler.schedule(() -> connect(ctx), waitSec, TimeUnit.SECONDS);
    }

    private static class TradeContext {
        final String symbol;
        volatile WebSocket webSocket;
        final java.util.concurrent.atomic.AtomicInteger reconnectAttempts =
                new java.util.concurrent.atomic.AtomicInteger(0);
        final java.util.concurrent.atomic.AtomicInteger refCount =
                new java.util.concurrent.atomic.AtomicInteger(0);
        final java.util.concurrent.atomic.AtomicLong lastMessageAtMs =
                new java.util.concurrent.atomic.AtomicLong(0);
        TradeContext(String symbol) { this.symbol = symbol; }
    }

    private class AggTradeWsListener extends WebSocketListener {
        private final TradeContext ctx;
        AggTradeWsListener(TradeContext ctx) { this.ctx = ctx; }

        @Override
        public void onOpen(WebSocket ws, Response resp) {
            ctx.reconnectAttempts.set(0);
            log.info("[AggTrade] symbol={} connected", ctx.symbol);
        }

        @Override
        public void onMessage(WebSocket ws, String text) {
            ctx.lastMessageAtMs.set(System.currentTimeMillis());
            try {
                JSONObject j = JSON.parseObject(text);
                // 币安 aggTrade: {"e":"aggTrade","s":"ETHUSDC","p":"...","q":"...","m":true/false,"E":..,"T":..}
                // ⚠️ m 字段语义："Was the buyer the market maker?"
                //   m=true → buyer 是 maker（卖方是 taker，砸盘）→ makerIsSeller=false
                //   m=false → seller 是 maker（买方是 taker，扫盘）→ makerIsSeller=true
                boolean buyerIsMaker = j.getBooleanValue("m");
                AggTrade t = AggTrade.builder()
                        .symbol(j.getString("s"))
                        .price(new BigDecimal(j.getString("p")))
                        .qty(new BigDecimal(j.getString("q")))
                        .makerIsSeller(!buyerIsMaker)   // 反转：m=true → makerIsSeller=false
                        .timeMs(j.containsKey("T") ? j.getLongValue("T") : System.currentTimeMillis())
                        .build();
                for (AggTradeListener l : listeners) {
                    try { l.onAggTrade(t); } catch (Exception ex) {
                        log.warn("[AggTrade] listener error: {}", ex.getMessage());
                    }
                }
            } catch (Exception ex) {
                log.debug("[AggTrade] parse error: {}", ex.getMessage());
            }
        }

        @Override
        public void onClosed(WebSocket ws, int code, String reason) {
            log.info("[AggTrade] symbol={} closed", ctx.symbol);
            if (subscriptions.containsKey(ctx.symbol)) scheduleReconnect(ctx);
        }

        @Override
        public void onFailure(WebSocket ws, Throwable t, Response resp) {
            log.warn("[AggTrade] symbol={} failure: {}", ctx.symbol, t.getMessage());
            if (subscriptions.containsKey(ctx.symbol)) scheduleReconnect(ctx);
        }
    }
}

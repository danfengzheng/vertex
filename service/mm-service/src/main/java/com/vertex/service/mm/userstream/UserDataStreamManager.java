package com.vertex.service.mm.userstream;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.vertex.service.mm.config.MmProperties;
import com.vertex.service.mm.userstream.event.AccountUpdateEvent;
import com.vertex.service.mm.userstream.event.OrderTradeUpdateEvent;
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
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 币安 futures USDC-M 用户数据流管理器。
 * <p>
 * 每个账户维护一个 listenKey + 一条 WebSocket 连接。
 * 负责：
 * <ul>
 *   <li>初次建连 —— 拿 listenKey → 建 WS</li>
 *   <li>keepalive —— {@link MmProperties#getListenKeyKeepaliveSec()} 定时续期</li>
 *   <li>断连重连 —— 指数退避</li>
 *   <li>事件路由 —— 分发给 {@link UserStreamListener}</li>
 *   <li>幂等去重 —— 按 event time + 订单 id 双键</li>
 * </ul>
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserDataStreamManager {

    private final MmProperties properties;
    private final ListenKeyManager listenKeyManager;

    /** 所有注册的 listener，事件到达时全体分发 */
    private final List<UserStreamListener> listeners = new CopyOnWriteArrayList<>();

    /** 每个账户一个连接状态 */
    private final ConcurrentMap<Long, StreamContext> streams = new ConcurrentHashMap<>();

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2,
            r -> {
                Thread t = new Thread(r, "mm-user-stream-scheduler");
                t.setDaemon(true);
                return t;
            });

    private final OkHttpClient wsClient = new OkHttpClient.Builder()
            .pingInterval(60, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .build();

    // ─── Public API ──────────────────────────────────────────

    /**
     * 注册事件监听器。可多次注册（GridEngine、DeltaMonitor 等各自注册）。
     */
    public void addListener(UserStreamListener listener) {
        listeners.add(listener);
    }

    /**
     * 为某账户启动私有流订阅。同一 accountId 重复调用是幂等的。
     */
    public synchronized void start(long accountId, String apiKey) {
        StreamContext existing = streams.get(accountId);
        if (existing != null && existing.connected.get()) {
            log.info("[UserStream] account={} already connected, skip start", accountId);
            return;
        }
        StreamContext ctx = new StreamContext(accountId, apiKey);
        streams.put(accountId, ctx);
        connect(ctx);
    }

    /**
     * 停止某账户的私有流。
     */
    public synchronized void stop(long accountId) {
        StreamContext ctx = streams.remove(accountId);
        if (ctx == null) return;
        if (ctx.keepaliveTask != null) ctx.keepaliveTask.cancel(false);
        if (ctx.webSocket != null) {
            try {
                ctx.webSocket.close(1000, "stopped");
            } catch (Exception ex) {
                log.warn("[UserStream] account={} close WS error: {}", accountId, ex.getMessage());
            }
        }
        try {
            listenKeyManager.close(ctx.apiKey);
        } catch (Exception ex) {
            log.warn("[UserStream] account={} close listenKey error: {}", accountId, ex.getMessage());
        }
        log.info("[UserStream] account={} stopped", accountId);
    }

    // ─── Internal ────────────────────────────────────────────

    private void connect(StreamContext ctx) {
        try {
            String listenKey = listenKeyManager.create(ctx.apiKey);
            ctx.listenKey = listenKey;

            String wsUrl = properties.getBinanceUserStreamWsBase() + "/" + listenKey;
            Request req = new Request.Builder().url(wsUrl).build();

            WebSocket socket = wsClient.newWebSocket(req, new StreamWsListener(ctx));
            ctx.webSocket = socket;

            // 调度 keepalive
            if (ctx.keepaliveTask != null) ctx.keepaliveTask.cancel(false);
            int intervalSec = Math.max(60, properties.getListenKeyKeepaliveSec());
            ctx.keepaliveTask = scheduler.scheduleAtFixedRate(
                    () -> safeKeepalive(ctx),
                    intervalSec, intervalSec, TimeUnit.SECONDS);

            log.info("[UserStream] account={} connecting: url={}", ctx.accountId, mask(wsUrl));
        } catch (Exception ex) {
            log.error("[UserStream] account={} connect failed: {}", ctx.accountId, ex.getMessage(), ex);
            scheduleReconnect(ctx);
        }
    }

    private void safeKeepalive(StreamContext ctx) {
        try {
            listenKeyManager.keepalive(ctx.apiKey);
            log.debug("[UserStream] account={} listenKey keepalive ok", ctx.accountId);
        } catch (Exception ex) {
            log.warn("[UserStream] account={} keepalive failed: {}. Will reconnect.",
                    ctx.accountId, ex.getMessage());
            forceReconnect(ctx);
        }
    }

    private void scheduleReconnect(StreamContext ctx) {
        long waitSec = Math.min(properties.getWsReconnectMaxSec(),
                properties.getWsReconnectInitialSec() * (1L << Math.min(6, ctx.reconnectAttempts.get())));
        ctx.reconnectAttempts.incrementAndGet();
        log.info("[UserStream] account={} reconnect in {}s (attempt {})",
                ctx.accountId, waitSec, ctx.reconnectAttempts.get());
        scheduler.schedule(() -> connect(ctx), waitSec, TimeUnit.SECONDS);
    }

    private void forceReconnect(StreamContext ctx) {
        try {
            if (ctx.webSocket != null) ctx.webSocket.close(1000, "reconnect");
        } catch (Exception ignored) {}
        ctx.connected.set(false);
        scheduleReconnect(ctx);
    }

    private static String mask(String url) {
        int lastSlash = url.lastIndexOf('/');
        if (lastSlash < 0 || lastSlash >= url.length() - 8) return url;
        return url.substring(0, lastSlash + 6) + "..." + url.substring(url.length() - 4);
    }

    // ─── Per-account context ─────────────────────────────────

    private static class StreamContext {
        final long accountId;
        final String apiKey;
        volatile String listenKey;
        volatile WebSocket webSocket;
        volatile ScheduledFuture<?> keepaliveTask;
        final java.util.concurrent.atomic.AtomicBoolean connected =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        final java.util.concurrent.atomic.AtomicInteger reconnectAttempts =
                new java.util.concurrent.atomic.AtomicInteger(0);

        /** 上一次处理事件的 E（event time），防延迟事件覆盖新状态 */
        final AtomicLong lastProcessedEventTime = new AtomicLong(0);
        /** 已处理的 orderId + eventTime 组合（简易去重，容量有限）*/
        final AtomicReference<String> lastOrderKey = new AtomicReference<>("");

        StreamContext(long accountId, String apiKey) {
            this.accountId = accountId;
            this.apiKey = apiKey;
        }
    }

    private class StreamWsListener extends WebSocketListener {
        private final StreamContext ctx;

        StreamWsListener(StreamContext ctx) {
            this.ctx = ctx;
        }

        @Override
        public void onOpen(WebSocket webSocket, Response response) {
            ctx.connected.set(true);
            int attempts = ctx.reconnectAttempts.getAndSet(0);
            log.info("[UserStream] account={} connected (attempts={})", ctx.accountId, attempts);
            if (attempts > 0) {
                for (UserStreamListener l : listeners) {
                    try { l.onReconnected(); } catch (Exception ex) {
                        log.warn("[UserStream] listener onReconnected error: {}", ex.getMessage());
                    }
                }
            }
        }

        @Override
        public void onMessage(WebSocket webSocket, String text) {
            try {
                JSONObject json = JSON.parseObject(text);
                String eventType = json.getString("e");
                if (eventType == null) {
                    log.debug("[UserStream] account={} unknown message (no e field): {}", ctx.accountId, text);
                    return;
                }
                long eventTime = json.getLongValue("E");
                switch (eventType) {
                    case "ORDER_TRADE_UPDATE" -> handleOrderUpdate(json, eventTime);
                    case "ACCOUNT_UPDATE"     -> handleAccountUpdate(json, eventTime);
                    case "listenKeyExpired"   -> handleListenKeyExpired();
                    case "MARGIN_CALL"        -> handleMarginCall(text);
                    default -> log.debug("[UserStream] account={} ignoring event type: {}", ctx.accountId, eventType);
                }
            } catch (Exception ex) {
                log.warn("[UserStream] account={} onMessage parse error: {}", ctx.accountId, ex.getMessage());
            }
        }

        @Override
        public void onClosing(WebSocket webSocket, int code, String reason) {
            log.info("[UserStream] account={} closing: code={}, reason={}", ctx.accountId, code, reason);
            ctx.connected.set(false);
        }

        @Override
        public void onClosed(WebSocket webSocket, int code, String reason) {
            log.info("[UserStream] account={} closed: code={}, reason={}", ctx.accountId, code, reason);
            ctx.connected.set(false);
            if (streams.containsKey(ctx.accountId)) {
                scheduleReconnect(ctx);
            }
        }

        @Override
        public void onFailure(WebSocket webSocket, Throwable t, Response response) {
            log.warn("[UserStream] account={} failure: {}", ctx.accountId, t.getMessage());
            ctx.connected.set(false);
            if (streams.containsKey(ctx.accountId)) {
                scheduleReconnect(ctx);
            }
        }

        private void handleOrderUpdate(JSONObject root, long eventTime) {
            JSONObject o = root.getJSONObject("o");
            if (o == null) return;
            long orderId = o.getLongValue("i");
            String dedupKey = eventTime + ":" + orderId + ":" + o.getString("x");
            if (dedupKey.equals(ctx.lastOrderKey.get())) return;
            ctx.lastOrderKey.set(dedupKey);

            OrderTradeUpdateEvent evt = OrderTradeUpdateEvent.builder()
                    .eventTimeMs(eventTime)
                    .transactionTimeMs(root.getLongValue("T"))
                    .symbol(o.getString("s"))
                    .exchangeOrderId(orderId)
                    .clientOrderId(o.getString("c"))
                    .side(o.getString("S"))
                    .positionSide(o.getString("ps"))
                    .type(o.getString("o"))
                    .timeInForce(o.getString("f"))
                    .originalQty(decimal(o, "q"))
                    .originalPrice(decimal(o, "p"))
                    .lastFilledQty(decimal(o, "l"))
                    .lastFilledPrice(decimal(o, "L"))
                    .cumulativeFilledQty(decimal(o, "z"))
                    .cumulativeQuote(decimal(o, "Z"))
                    .orderStatus(o.getString("X"))
                    .execType(o.getString("x"))
                    .fee(decimal(o, "n"))
                    .feeAsset(o.getString("N"))
                    .maker(o.getBooleanValue("m"))
                    .build();

            for (UserStreamListener l : listeners) {
                try { l.onOrderUpdate(evt); } catch (Exception ex) {
                    log.warn("[UserStream] listener onOrderUpdate error: {}", ex.getMessage());
                }
            }
        }

        private void handleAccountUpdate(JSONObject root, long eventTime) {
            JSONObject a = root.getJSONObject("a");
            if (a == null) return;
            AccountUpdateEvent evt = AccountUpdateEvent.builder()
                    .eventTimeMs(eventTime)
                    .transactionTimeMs(root.getLongValue("T"))
                    .reason(a.getString("m"))
                    .balances(parseBalances(a.getJSONArray("B")))
                    .positions(parsePositions(a.getJSONArray("P")))
                    .build();
            for (UserStreamListener l : listeners) {
                try { l.onAccountUpdate(evt); } catch (Exception ex) {
                    log.warn("[UserStream] listener onAccountUpdate error: {}", ex.getMessage());
                }
            }
        }

        private void handleListenKeyExpired() {
            log.warn("[UserStream] account={} listenKey expired, force reconnect", ctx.accountId);
            for (UserStreamListener l : listeners) {
                try { l.onListenKeyExpired(); } catch (Exception ignored) {}
            }
            forceReconnect(ctx);
        }

        private void handleMarginCall(String raw) {
            log.error("[UserStream] account={} MARGIN_CALL received: {}", ctx.accountId, raw);
            for (UserStreamListener l : listeners) {
                try { l.onMarginCall(raw); } catch (Exception ignored) {}
            }
        }
    }

    private static BigDecimal decimal(JSONObject o, String key) {
        String v = o.getString(key);
        return v == null ? null : new BigDecimal(v);
    }

    private static List<AccountUpdateEvent.BalanceDelta> parseBalances(JSONArray arr) {
        List<AccountUpdateEvent.BalanceDelta> out = new ArrayList<>();
        if (arr == null) return out;
        for (int i = 0; i < arr.size(); i++) {
            JSONObject b = arr.getJSONObject(i);
            out.add(AccountUpdateEvent.BalanceDelta.builder()
                    .asset(b.getString("a"))
                    .walletBalance(decimal(b, "wb"))
                    .crossWalletBalance(decimal(b, "cw"))
                    .balanceChange(decimal(b, "bc"))
                    .build());
        }
        return out;
    }

    private static List<AccountUpdateEvent.PositionDelta> parsePositions(JSONArray arr) {
        List<AccountUpdateEvent.PositionDelta> out = new ArrayList<>();
        if (arr == null) return out;
        for (int i = 0; i < arr.size(); i++) {
            JSONObject p = arr.getJSONObject(i);
            out.add(AccountUpdateEvent.PositionDelta.builder()
                    .symbol(p.getString("s"))
                    .positionAmt(decimal(p, "pa"))
                    .entryPrice(decimal(p, "ep"))
                    .accumulatedRealized(decimal(p, "cr"))
                    .unrealizedPnl(decimal(p, "up"))
                    .marginType(p.getString("mt"))
                    .isolatedWallet(decimal(p, "iw"))
                    .positionSide(p.getString("ps"))
                    .build());
        }
        return out;
    }
}

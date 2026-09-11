package com.vertex.service.mm.grid.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.vertex.service.mm.binance.BinanceTimeSync;
import com.vertex.service.mm.config.MmProperties;
import com.vertex.service.mm.grid.entity.GridOrder;
import com.vertex.service.mm.grid.entity.GridStrategy;
import com.vertex.service.mm.grid.mapper.GridOrderMapper;
import com.vertex.service.mm.grid.mapper.GridStrategyMapper;
import com.vertex.service.mm.userstream.UserStreamListener;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * WS 重连后的全量对账 —— 拉交易所 openOrders 对比本地 grid_order 表补差。
 * <p>
 * 场景：私有 WS 断线 30 秒到几分钟内，可能丢失 ORDER_TRADE_UPDATE 事件。
 * 重连后调本方法：
 * <ol>
 *   <li>REST GET /fapi/v1/openOrders → 拉当前所有活跃挂单</li>
 *   <li>对比 grid_order 表 status=LIVE 记录</li>
 *   <li>差异处理：
 *     <ul>
 *       <li>本地 LIVE 但远端不在 → 已成交/被撤，标 FILLED 或 CANCELED</li>
 *       <li>远端存在但本地 status != LIVE → 状态不同步，更新</li>
 *     </ul>
 *   </li>
 * </ol>
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WsReconciliationService implements UserStreamListener {

    private final MmProperties properties;
    private final BinanceTimeSync timeSync;
    private final GridStrategyMapper strategyMapper;
    private final GridOrderMapper orderMapper;
    private final AccountCredentialResolver credentialResolver;
    private final GridEventLogger eventLogger;

    /** 全局最短对账间隔（防重连风暴）*/
    private final AtomicLong lastReconcileAt = new AtomicLong(0);

    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build();

    @Override
    public void onReconnected() {
        long now = System.currentTimeMillis();
        long lastMs = lastReconcileAt.get();
        long minInterval = properties.getReconcileMinIntervalSec() * 1000L;
        if (now - lastMs < minInterval) {
            log.info("[Reconcile] skipped, last reconcile only {}ms ago", now - lastMs);
            return;
        }
        if (!lastReconcileAt.compareAndSet(lastMs, now)) return;

        log.warn("[Reconcile] WS reconnected → running full order reconciliation");
        try {
            reconcileAllStrategies();
        } catch (Exception ex) {
            log.error("[Reconcile] failed: {}", ex.getMessage(), ex);
        }
    }

    /**
     * 手动触发（管理员接口 or 定时兜底可调）。
     */
    public void reconcileAllStrategies() {
        // 只对 LIVE 策略对账；PAPER 策略的单只存在于本地 PaperOrderBook，
        // 用真实 API 查 openOrders 会把所有 paper 单标为 UNKNOWN_RECONCILE 破坏模拟。
        List<GridStrategy> running = strategyMapper.selectList(new LambdaQueryWrapper<GridStrategy>()
                .eq(GridStrategy::getStatus, "RUNNING"));
        running.removeIf(s -> "PAPER".equalsIgnoreCase(s.getExecutionMode()));
        if (running.isEmpty()) {
            log.info("[Reconcile] no LIVE strategies to reconcile");
            return;
        }
        Map<Long, Set<Long>> exchangeOrderIdsByAccount = new HashMap<>();

        // 按 account 分组拉 openOrders（相同账户不重复调 API）
        for (GridStrategy s : running) {
            if (exchangeOrderIdsByAccount.containsKey(s.getAccountId())) continue;
            try {
                var creds = credentialResolver.resolve(s.getAccountId());
                Set<Long> ids = fetchOpenOrderIds(creds.apiKey(), creds.apiSecret(), s.getSymbol());
                exchangeOrderIdsByAccount.put(s.getAccountId(), ids);
            } catch (Exception ex) {
                log.warn("[Reconcile] fetch openOrders for account={} failed: {}",
                        s.getAccountId(), ex.getMessage());
            }
        }

        int missingCount = 0;
        for (GridStrategy s : running) {
            Set<Long> remoteIds = exchangeOrderIdsByAccount.getOrDefault(s.getAccountId(), Set.of());
            List<GridOrder> localLive = orderMapper.selectList(new LambdaQueryWrapper<GridOrder>()
                    .eq(GridOrder::getGridStrategyId, s.getId())
                    .eq(GridOrder::getStatus, "LIVE"));

            for (GridOrder local : localLive) {
                if (local.getExchangeOrderId() == null) continue;
                Long localId;
                try { localId = Long.parseLong(local.getExchangeOrderId()); }
                catch (NumberFormatException e) { continue; }

                if (!remoteIds.contains(localId)) {
                    // 本地 LIVE 但远端不在 → 可能成交或撤销，改 status 让下次 fill event 更新
                    orderMapper.update(null, new LambdaUpdateWrapper<GridOrder>()
                            .eq(GridOrder::getId, local.getId())
                            .set(GridOrder::getStatus, "UNKNOWN_RECONCILE")
                            .set(GridOrder::getUpdateTime, LocalDateTime.now()));
                    missingCount++;
                }
            }
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put("strategiesChecked", running.size());
        payload.put("accountsChecked", exchangeOrderIdsByAccount.size());
        payload.put("missingLocalOrders", missingCount);
        eventLogger.info(-1L, "RECONCILE_COMPLETE", payload);
        log.info("[Reconcile] complete: strategies={}, missing={}", running.size(), missingCount);
    }

    // ─── REST: 拉 openOrders ────────────────────────────────

    private Set<Long> fetchOpenOrderIds(String apiKey, String apiSecret, String symbol) throws IOException {
        StringBuilder params = new StringBuilder();
        params.append("symbol=").append(symbol);
        params.append("&recvWindow=10000&timestamp=").append(timeSync.now());
        String signature = sign(params.toString(), apiSecret);
        params.append("&signature=").append(signature);

        Request req = new Request.Builder()
                .url(properties.getBinanceUsdcmBaseUrl() + "/fapi/v1/openOrders?" + params)
                .get()
                .addHeader("X-MBX-APIKEY", apiKey)
                .build();

        try (Response resp = http.newCall(req).execute()) {
            String body = resp.body() == null ? "" : resp.body().string();
            if (!resp.isSuccessful()) {
                throw new IOException("openOrders HTTP " + resp.code() + ": " + body);
            }
            JSONArray arr = JSON.parseArray(body);
            Set<Long> ids = new HashSet<>();
            for (int i = 0; i < arr.size(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Long id = o.getLong("orderId");
                if (id != null) ids.add(id);
            }
            return ids;
        }
    }

    private String sign(String data, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            throw new RuntimeException("sign error", e);
        }
    }
}

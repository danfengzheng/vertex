package com.vertex.service.farb.signal;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.vertex.service.farb.config.FarbProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 资金费套利 - Funding 历史采集。
 * <p>
 * 独立于 {@link FarbDataCollector}：
 * <ul>
 *   <li>启动时（有 universe 后）批量拉每 symbol 最近 100 次结算，落 RocksDB + cache</li>
 *   <li>之后每 4h 增量拉一次（覆盖过去 32h 内所有已结算 event），保证 cache 持续更新</li>
 * </ul>
 * 用 fundingIntervalHours 支持不同结算周期。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FarbFundingHistoryPuller {

    private static final int INITIAL_LIMIT     = 100;   // 首次拉 100 次
    private static final int INCREMENTAL_LIMIT = 8;     // 增量拉最近 8 次即够（够覆盖 4h * 2 = 8h 数据）

    private final FarbProperties properties;
    private final FarbDataCollector dataCollector;   // 复用其 universe
    private final FarbSignalStore signalStore;
    private final FarbSignalCache signalCache;
    private final com.vertex.service.farb.mapper.FarbPositionMapper positionMapper;

    private OkHttpClient http;

    /** 每 symbol 的 fundingIntervalHours 缓存（1/4/8）。避免重复拉 fundingInfo */
    private final Map<String, Integer> intervalCache = new ConcurrentHashMap<>();

    /** 首次冷启动完成后置 true，之后走增量 */
    private volatile boolean bootstrapped = false;

    @PostConstruct
    public void init() {
        this.http = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofMillis(properties.getPullTimeoutMs()))
                .readTimeout(Duration.ofMillis(properties.getPullTimeoutMs()))
                .build();
        log.info("[FarbFundingHistory] initialized");
    }

    // ─── 定时冷启动 + 增量 ────────────────────────────────────────────────

    /** 每 4h 增量刷（同时兼作首次 bootstrap） */
    @Scheduled(fixedDelayString = "PT30M", initialDelayString = "PT30S")
    public void pullAll() {
        if (dataCollector.isShuttingDown() || !properties.isDataCollectorEnabled()) return;
        var universe = new java.util.HashSet<>(dataCollector.getUniverse());
        // 补上所有 OPEN pair 的 symbol（可能不在当前 universe 里但仍需继续拉 funding）
        try {
            var openSyms = positionMapper.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<
                            com.vertex.service.farb.entity.FarbPosition>()
                            .eq(com.vertex.service.farb.entity.FarbPosition::getStatus, "OPEN")
                            .select(com.vertex.service.farb.entity.FarbPosition::getSymbol))
                    .stream().map(com.vertex.service.farb.entity.FarbPosition::getSymbol)
                    .collect(java.util.stream.Collectors.toSet());
            universe.addAll(openSyms);
        } catch (Exception ex) {
            log.debug("[FarbFundingHistory] fetch open pair symbols failed: {}", ex.getMessage());
        }
        if (universe.isEmpty()) {
            log.debug("[FarbFundingHistory] universe empty, skip");
            return;
        }
        int limit = bootstrapped ? INCREMENTAL_LIMIT : INITIAL_LIMIT;

        // 首次先拉一次 intervalCache（一次性拉全市场）
        if (!bootstrapped) {
            fetchIntervals();
        }

        // 借用 DataCollector 的信号量 + workerPool 做并发拉取（避免 429）
        java.util.concurrent.atomic.AtomicInteger okCount = new java.util.concurrent.atomic.AtomicInteger();
        java.util.List<java.util.concurrent.CompletableFuture<Void>> futures = new java.util.ArrayList<>();
        final int finalLimit = limit;
        for (String sym : universe) {
            futures.add(java.util.concurrent.CompletableFuture.runAsync(() -> {
                try {
                    dataCollector.acquireSlot();
                    try {
                        pullOne(sym, finalLimit);
                        okCount.incrementAndGet();
                    } finally {
                        dataCollector.releaseSlot();
                    }
                } catch (Exception ex) {
                    log.debug("[FarbFundingHistory] pull {} failed: {}", sym, ex.getMessage());
                }
            }, dataCollector.getWorkerPool()));
        }
        try {
            java.util.concurrent.CompletableFuture.allOf(futures.toArray(
                    new java.util.concurrent.CompletableFuture[0]))
                    .get(15, java.util.concurrent.TimeUnit.MINUTES);
        } catch (Exception ignored) {}
        log.info("[FarbFundingHistory] pulled {} / {} symbols (limit={}, bootstrapped={})",
                okCount.get(), universe.size(), limit, bootstrapped);
        bootstrapped = true;
    }

    private void fetchIntervals() {
        try {
            String json = restGet(properties.getBinanceUsdmBaseUrl() + "/fapi/v1/fundingInfo");
            JSONArray arr = JSON.parseArray(json);
            for (Object o : arr) {
                JSONObject j = (JSONObject) o;
                String sym = j.getString("symbol");
                Integer hours = j.getInteger("fundingIntervalHours");
                if (sym != null && hours != null) {
                    intervalCache.put(sym, hours);
                }
            }
            log.info("[FarbFundingHistory] fundingInfo loaded for {} symbols", intervalCache.size());
        } catch (Exception ex) {
            log.warn("[FarbFundingHistory] fundingInfo fetch failed: {}", ex.getMessage());
        }
    }

    private void pullOne(String symbol, int limit) throws Exception {
        String url = properties.getBinanceUsdmBaseUrl()
                + "/fapi/v1/fundingRate?symbol=" + symbol
                + "&limit=" + limit;
        String json = restGet(url);
        JSONArray arr = JSON.parseArray(json);
        // 默认 8h，除非 intervalCache 有值
        int intervalHours = intervalCache.getOrDefault(symbol, 8);
        for (Object o : arr) {
            JSONObject j = (JSONObject) o;
            long settleTs = j.getLong("fundingTime");
            double rate = j.getBigDecimal("fundingRate").doubleValue();
            JSONObject value = new JSONObject();
            value.put("rate", rate);
            value.put("intervalHours", intervalHours);
            signalStore.save(FarbSignalStore.PFX_FUNDING, symbol, settleTs, value);
            signalCache.putFunding(symbol, settleTs,
                    new FarbSignalCache.FundingRecord(rate, intervalHours));
        }
    }

    private String restGet(String url) throws java.io.IOException {
        Request req = new Request.Builder().url(url).get().build();
        try (Response resp = http.newCall(req).execute()) {
            if (!resp.isSuccessful()) {
                throw new java.io.IOException("HTTP " + resp.code() + " " + url);
            }
            return resp.body().string();
        }
    }

    /** 供 Scanner 查询某 symbol 的结算周期（未知时兜底 8h）*/
    public int getFundingIntervalHours(String symbol) {
        return intervalCache.getOrDefault(symbol, 8);
    }
}

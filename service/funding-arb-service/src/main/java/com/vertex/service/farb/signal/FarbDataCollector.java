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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 资金费套利 - 全市场信号数据采集器。
 * <p>
 * 按不同频率并发拉取 Binance USDM 公开数据，写入 {@link FarbSignalStore}：
 * <ul>
 *   <li>Premium Index (30s)：全市场一次拉，含 markPrice/indexPrice/fundingRate/nextSettleTs</li>
 *   <li>Open Interest (5min)：每 symbol 逐个拉</li>
 *   <li>Long/Short Ratio (5min)：每 symbol 逐个拉（大户仓位版）</li>
 *   <li>Taker Buy/Sell Ratio (15min)：每 symbol 逐个拉</li>
 *   <li>K 线 vol (60min)：每 symbol 拉 168 条 1h K 线算 realized vol</li>
 *   <li>可用交集 (60min)：从 exchangeInfo + 24hr ticker 算 spot ∩ usdm ∩ 流动性达标</li>
 * </ul>
 * <p>
 * 依赖 {@code vertex.farb.enabled=true} 且 {@code vertex.farb.dataCollectorEnabled=true} 才启动。
 * 为独立于策略跑，即使策略未启用也可只跑 collector 累积历史数据供未来回测。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FarbDataCollector {

    private final FarbProperties properties;
    private final FarbSignalStore signalStore;
    private final FarbSignalCache signalCache;

    private OkHttpClient http;
    private ExecutorService workerPool;
    private Semaphore pullSemaphore;

    /** 可用交集 symbol（USDT quote，spot 和 usdm 都存在，volume 达标）*/
    private final Set<String> universe = new CopyOnWriteArraySet<>();

    @PostConstruct
    public void init() {
        this.http = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofMillis(properties.getPullTimeoutMs()))
                .readTimeout(Duration.ofMillis(properties.getPullTimeoutMs()))
                .build();
        this.workerPool = Executors.newFixedThreadPool(properties.getConcurrentPullers(),
                r -> {
                    Thread t = new Thread(r, "farb-puller");
                    t.setDaemon(true);
                    return t;
                });
        this.pullSemaphore = new Semaphore(properties.getConcurrentPullers());
        if (properties.isDataCollectorEnabled()) {
            log.info("[FarbCollector] initialized. concurrentPullers={} timeoutMs={}",
                    properties.getConcurrentPullers(), properties.getPullTimeoutMs());
            // 冷启动：先刷 universe，然后从 RocksDB 把已有信号 hydrate 到内存 cache
            workerPool.submit(() -> {
                refreshUniverse();
                if (!universe.isEmpty()) {
                    int total = signalCache.hydrateFromStore(signalStore, universe);
                    log.info("[FarbCollector] cache hydrated {} entries from RocksDB", total);
                }
            });
        } else {
            log.info("[FarbCollector] disabled by config vertex.farb.dataCollectorEnabled=false");
        }
    }

    // ─── 1. 可用交集刷新（每 60 min）────────────────────────────────────────

    @Scheduled(fixedDelayString = "${vertex.farb.universe-refresh-min:60}", timeUnit = TimeUnit.MINUTES)
    public void refreshUniverse() {
        if (shuttingDown || !properties.isDataCollectorEnabled()) return;
        try {
            // 1. 拉 usdm exchangeInfo
            Set<String> usdmSymbols = new HashSet<>();
            String usdmJson = restGet(properties.getBinanceUsdmBaseUrl() + "/fapi/v1/exchangeInfo");
            for (Object o : JSON.parseObject(usdmJson).getJSONArray("symbols")) {
                JSONObject s = (JSONObject) o;
                if ("TRADING".equals(s.getString("status"))
                        && "PERPETUAL".equals(s.getString("contractType"))
                        && "USDT".equals(s.getString("quoteAsset"))) {
                    usdmSymbols.add(s.getString("symbol"));
                }
            }

            // 2. 拉 spot exchangeInfo，取交集
            Set<String> spotSymbols = new HashSet<>();
            String spotJson = restGet(properties.getBinanceSpotBaseUrl() + "/api/v3/exchangeInfo");
            for (Object o : JSON.parseObject(spotJson).getJSONArray("symbols")) {
                JSONObject s = (JSONObject) o;
                if ("TRADING".equals(s.getString("status"))
                        && "USDT".equals(s.getString("quoteAsset"))) {
                    spotSymbols.add(s.getString("symbol"));
                }
            }
            usdmSymbols.retainAll(spotSymbols);

            // 3. 24h volume 过滤
            Set<String> filtered = new HashSet<>();
            String tickerJson = restGet(properties.getBinanceUsdmBaseUrl() + "/fapi/v1/ticker/24hr");
            JSONArray ticks = JSON.parseArray(tickerJson);
            for (Object o : ticks) {
                JSONObject t = (JSONObject) o;
                String sym = t.getString("symbol");
                if (!usdmSymbols.contains(sym)) continue;
                double notional = t.getBigDecimal("quoteVolume").doubleValue();
                // hard code min 10M USD；上层策略可再 filter 更高
                if (notional >= 10_000_000) {
                    filtered.add(sym);
                }
            }

            universe.clear();
            universe.addAll(filtered);
            log.info("[FarbCollector] universe refreshed: usdm={} spot∩usdm={} volumeOk={}",
                    usdmSymbols.size() + spotSymbols.size(), usdmSymbols.size(), filtered.size());
        } catch (Exception ex) {
            log.warn("[FarbCollector] refreshUniverse failed: {}", ex.getMessage());
        }
    }

    // ─── 2. Premium Index 全市场（每 30s）───────────────────────────────────

    @Scheduled(fixedDelayString = "${vertex.farb.premium-index-pull-sec:30}", timeUnit = TimeUnit.SECONDS)
    public void pullPremiumIndex() {
        if (shuttingDown || !properties.isDataCollectorEnabled() || universe.isEmpty()) return;
        try {
            String json = restGet(properties.getBinanceUsdmBaseUrl() + "/fapi/v1/premiumIndex");
            JSONArray arr = JSON.parseArray(json);
            long now = System.currentTimeMillis();
            int saved = 0;
            for (Object o : arr) {
                JSONObject j = (JSONObject) o;
                String sym = j.getString("symbol");
                if (!universe.contains(sym)) continue;
                JSONObject value = new JSONObject();
                value.put("markPrice",       j.getBigDecimal("markPrice"));
                value.put("indexPrice",      j.getBigDecimal("indexPrice"));
                value.put("fundingRate",     j.getBigDecimal("lastFundingRate"));
                value.put("nextSettleTs",    j.getLong("nextFundingTime"));
                signalStore.save(FarbSignalStore.PFX_PREM, sym, now, value);
                // 同步内存 cache
                signalCache.putPremium(sym, now, new FarbSignalCache.PremiumRecord(
                        j.getBigDecimal("markPrice").doubleValue(),
                        j.getBigDecimal("indexPrice").doubleValue(),
                        j.getBigDecimal("lastFundingRate").doubleValue(),
                        j.getLong("nextFundingTime")));
                saved++;
            }
            log.debug("[FarbCollector] premium pulled: {} symbols", saved);
        } catch (Exception ex) {
            log.warn("[FarbCollector] pullPremiumIndex failed: {}", ex.getMessage());
        }
    }

    // ─── 3. Open Interest + LSR（每 5 min）─────────────────────────────────

    @Scheduled(fixedDelayString = "${vertex.farb.oi-lsr-pull-min:5}", timeUnit = TimeUnit.MINUTES)
    public void pullOiAndLsr() {
        if (shuttingDown || !properties.isDataCollectorEnabled() || universe.isEmpty()) return;
        AtomicInteger oiOk = new AtomicInteger();
        AtomicInteger lsrOk = new AtomicInteger();
        long now = System.currentTimeMillis();
        long start = System.currentTimeMillis();

        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (String sym : universe) {
            futures.add(CompletableFuture.runAsync(() -> {
                try {
                    pullSemaphore.acquire();
                    try {
                        // OI
                        String oiJson = restGet(properties.getBinanceUsdmBaseUrl()
                                + "/fapi/v1/openInterest?symbol=" + sym);
                        JSONObject oi = JSON.parseObject(oiJson);
                        JSONObject oiValue = new JSONObject();
                        oiValue.put("openInterest", oi.getBigDecimal("openInterest"));
                        signalStore.save(FarbSignalStore.PFX_OI, sym, now, oiValue);
                        signalCache.putOi(sym, now, new FarbSignalCache.OiRecord(
                                oi.getBigDecimal("openInterest").doubleValue()));
                        oiOk.incrementAndGet();

                        // LSR
                        String lsrJson = restGet(properties.getBinanceUsdmBaseUrl()
                                + "/futures/data/topLongShortPositionRatio?symbol=" + sym + "&period=5m&limit=1");
                        JSONArray lsrArr = JSON.parseArray(lsrJson);
                        if (!lsrArr.isEmpty()) {
                            JSONObject l = lsrArr.getJSONObject(0);
                            JSONObject lsrValue = new JSONObject();
                            lsrValue.put("longShortRatio", l.getBigDecimal("longShortRatio"));
                            lsrValue.put("longAccount",    l.getBigDecimal("longAccount"));
                            lsrValue.put("shortAccount",   l.getBigDecimal("shortAccount"));
                            signalStore.save(FarbSignalStore.PFX_LSR, sym, now, lsrValue);
                            signalCache.putLsr(sym, now, new FarbSignalCache.LsrRecord(
                                    l.getBigDecimal("longShortRatio").doubleValue(),
                                    l.getBigDecimal("longAccount").doubleValue(),
                                    l.getBigDecimal("shortAccount").doubleValue()));
                            lsrOk.incrementAndGet();
                        }
                    } finally {
                        pullSemaphore.release();
                    }
                } catch (Exception ex) {
                    log.debug("[FarbCollector] OI/LSR pull failed sym={}: {}", sym, ex.getMessage());
                }
            }, workerPool));
        }
        // 等所有并发拉取完成，最多 4min（不阻塞下一轮）
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(4, TimeUnit.MINUTES);
        } catch (Exception ex) {
            log.warn("[FarbCollector] OI+LSR batch wait interrupted: {}", ex.getMessage());
        }
        log.info("[FarbCollector] OI+LSR pulled: oi={} lsr={} total={} tookMs={} usedWeight={}",
                oiOk.get(), lsrOk.get(), universe.size(),
                System.currentTimeMillis() - start, latestUsedWeight.get());
    }

    // ─── 4. Taker Buy/Sell Ratio（每 15 min）───────────────────────────────

    @Scheduled(fixedDelayString = "${vertex.farb.taker-bsr-pull-min:15}", timeUnit = TimeUnit.MINUTES)
    public void pullTakerBsr() {
        if (shuttingDown || !properties.isDataCollectorEnabled() || universe.isEmpty()) return;
        AtomicInteger ok = new AtomicInteger();
        long now = System.currentTimeMillis();
        long start = System.currentTimeMillis();

        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (String sym : universe) {
            futures.add(CompletableFuture.runAsync(() -> {
                try {
                    pullSemaphore.acquire();
                    try {
                        String json = restGet(properties.getBinanceUsdmBaseUrl()
                                + "/futures/data/takerlongshortRatio?symbol=" + sym + "&period=15m&limit=1");
                        JSONArray arr = JSON.parseArray(json);
                        if (!arr.isEmpty()) {
                            JSONObject t = arr.getJSONObject(0);
                            JSONObject v = new JSONObject();
                            v.put("buySellRatio", t.getBigDecimal("buySellRatio"));
                            v.put("buyVol",       t.getBigDecimal("buyVol"));
                            v.put("sellVol",      t.getBigDecimal("sellVol"));
                            signalStore.save(FarbSignalStore.PFX_TBSR, sym, now, v);
                            signalCache.putTbsr(sym, now, new FarbSignalCache.TakerBsrRecord(
                                    t.getBigDecimal("buySellRatio").doubleValue(),
                                    t.getBigDecimal("buyVol").doubleValue(),
                                    t.getBigDecimal("sellVol").doubleValue()));
                            ok.incrementAndGet();
                        }
                    } finally { pullSemaphore.release(); }
                } catch (Exception ex) {
                    log.debug("[FarbCollector] TakerBSR pull failed sym={}: {}", sym, ex.getMessage());
                }
            }, workerPool));
        }
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(10, TimeUnit.MINUTES);
        } catch (Exception ignored) {}
        log.info("[FarbCollector] TakerBSR pulled: {} / {} tookMs={}",
                ok.get(), universe.size(), System.currentTimeMillis() - start);
    }

    // ─── 5. Realized Vol（每 60 min）───────────────────────────────────────

    @Scheduled(fixedDelayString = "${vertex.farb.vol-pull-min:60}", timeUnit = TimeUnit.MINUTES)
    public void pullVol() {
        if (shuttingDown || !properties.isDataCollectorEnabled() || universe.isEmpty()) return;
        AtomicInteger ok = new AtomicInteger();
        long now = System.currentTimeMillis();
        long start = System.currentTimeMillis();

        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (String sym : universe) {
            futures.add(CompletableFuture.runAsync(() -> {
                try {
                    pullSemaphore.acquire();
                    try {
                        String json = restGet(properties.getBinanceUsdmBaseUrl()
                                + "/fapi/v1/klines?symbol=" + sym + "&interval=1h&limit=168");
                        JSONArray klines = JSON.parseArray(json);
                        if (klines.size() < 24) return;
                        double[] closes = new double[klines.size()];
                        for (int i = 0; i < klines.size(); i++) {
                            closes[i] = klines.getJSONArray(i).getBigDecimal(4).doubleValue();
                        }
                        double vol24 = realizedVolAnnualized(closes, closes.length - 24, closes.length);
                        double vol72 = realizedVolAnnualized(closes, Math.max(0, closes.length - 72), closes.length);
                        double vol7d = realizedVolAnnualized(closes, 0, closes.length);
                        JSONObject v = new JSONObject();
                        v.put("vol24h", vol24);
                        v.put("vol72h", vol72);
                        v.put("vol7d",  vol7d);
                        signalStore.save(FarbSignalStore.PFX_VOL, sym, now, v);
                        signalCache.putVol(sym, now, new FarbSignalCache.VolRecord(vol24, vol72, vol7d));
                        ok.incrementAndGet();
                    } finally { pullSemaphore.release(); }
                } catch (Exception ex) {
                    log.debug("[FarbCollector] Vol pull failed sym={}: {}", sym, ex.getMessage());
                }
            }, workerPool));
        }
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(30, TimeUnit.MINUTES);
        } catch (Exception ignored) {}
        log.info("[FarbCollector] Vol pulled: {} / {} tookMs={}",
                ok.get(), universe.size(), System.currentTimeMillis() - start);
    }

    // ─── 6. Funding history 增量（每次结算后）─ 简化：每 4h 刷一次 top-100 symbol
    // 完整实现在 Sprint 2 SignalCollector 里做，Sprint 1 略过（可用 REST 补齐）

    // ─── 内部工具 ───────────────────────────────────────────────────────────

    /** 全局 backoff 状态：遇到 429 后所有 puller 主动降速 */
    private volatile long backoffUntilMs = 0;

    /** rate limit 监控：从 X-MBX-USED-WEIGHT-1M 拿最新值 */
    private final AtomicInteger latestUsedWeight = new AtomicInteger(0);

    /**
     * 429 感知的 REST GET：
     * - 遇到 429 记录 Retry-After，全局 backoffUntilMs 生效，所有 puller 停 N 秒
     * - 成功时更新 X-MBX-USED-WEIGHT-1M 供监控
     * - 最多重试 3 次（指数退避 0.5s / 2s / 5s）
     */
    private String restGet(String url) throws java.io.IOException {
        // 若全局 backoff 生效，直接等
        long waitMs = backoffUntilMs - System.currentTimeMillis();
        if (waitMs > 0) {
            try { Thread.sleep(Math.min(waitMs, 30_000)); } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        Request req = new Request.Builder().url(url).get().build();
        int attempt = 0;
        long backoffMs = 500;
        while (attempt < 3) {
            try (Response resp = http.newCall(req).execute()) {
                // 更新监控
                String usedWeight = resp.header("X-MBX-USED-WEIGHT-1M");
                if (usedWeight != null) {
                    try { latestUsedWeight.set(Integer.parseInt(usedWeight)); } catch (Exception ignored) {}
                }
                if (resp.code() == 429 || resp.code() == 418) {
                    String retryAfter = resp.header("Retry-After", "1");
                    long secs = 1;
                    try { secs = Long.parseLong(retryAfter); } catch (Exception ignored) {}
                    long until = System.currentTimeMillis() + secs * 1000L;
                    backoffUntilMs = Math.max(backoffUntilMs, until);
                    log.warn("[FarbCollector] rate-limited HTTP {} url={} retryAfter={}s → backoff",
                            resp.code(), url, secs);
                    Thread.sleep(secs * 1000L);
                    attempt++;
                    continue;
                }
                if (!resp.isSuccessful()) {
                    throw new java.io.IOException("HTTP " + resp.code() + " " + url);
                }
                return resp.body().string();
            } catch (java.io.IOException | InterruptedException ex) {
                if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
                if (attempt == 2) throw new java.io.IOException("failed after 3 attempts: " + ex.getMessage(), ex);
                try { Thread.sleep(backoffMs); } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                backoffMs *= 3;
                attempt++;
            }
        }
        throw new java.io.IOException("unreachable");
    }

    /** 前端观测：当前 rate limit 用量 */
    public int getLatestUsedWeight() {
        return latestUsedWeight.get();
    }

    /** 供 FundingHistoryPuller 复用同一线程池，避免线程池/信号量重复分配 */
    public ExecutorService getWorkerPool() {
        return workerPool;
    }

    /**
     * 关闭前先停线程池，避免 RocksDB 已关但 puller 还在 put → SIGSEGV。
     * 顺序：等运行中的任务尽快结束（10s 上限）→ 强杀 → 主线程继续，让 Spring 再关 RocksDB。
     */
    @jakarta.annotation.PreDestroy
    public void shutdown() {
        shuttingDown = true;
        if (workerPool != null) {
            log.info("[FarbCollector] shutdown: stopping worker pool ...");
            workerPool.shutdown();
            try {
                if (!workerPool.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)) {
                    log.warn("[FarbCollector] worker pool did not terminate in 10s, forcing");
                    workerPool.shutdownNow();
                    workerPool.awaitTermination(3, java.util.concurrent.TimeUnit.SECONDS);
                }
            } catch (InterruptedException ie) {
                workerPool.shutdownNow();
                Thread.currentThread().interrupt();
            }
            log.info("[FarbCollector] worker pool stopped");
        }
    }

    /** 供调度方法感知，避免 Scheduled 在 shutdown 后继续跑 put */
    public boolean isShuttingDown() {
        return shuttingDown;
    }

    private volatile boolean shuttingDown = false;

    public void acquireSlot() throws InterruptedException {
        pullSemaphore.acquire();
    }

    public void releaseSlot() {
        pullSemaphore.release();
    }

    private double realizedVolAnnualized(double[] closes, int from, int toExclusive) {
        int n = toExclusive - from;
        if (n < 2) return 0.0;
        double[] rets = new double[n - 1];
        double mean = 0;
        for (int i = from + 1, k = 0; i < toExclusive; i++, k++) {
            rets[k] = Math.log(closes[i] / closes[i - 1]);
            mean += rets[k];
        }
        mean /= rets.length;
        double var = 0;
        for (double r : rets) var += (r - mean) * (r - mean);
        var /= Math.max(1, rets.length - 1);
        // 1h → 年化：sqrt(24 × 365) = 93.7
        return Math.sqrt(var) * Math.sqrt(24 * 365);
    }

    /** 供上层 Scanner 读取 universe */
    public Set<String> getUniverse() {
        return Collections.unmodifiableSet(universe);
    }
}

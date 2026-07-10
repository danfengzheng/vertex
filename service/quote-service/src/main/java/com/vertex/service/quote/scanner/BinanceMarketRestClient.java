package com.vertex.service.quote.scanner;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 币安现货全站行情扫描专用 REST 客户端。
 * <p>
 * 与业务用的 {@link com.vertex.service.quote.source.rest.BinanceRestClient} 分离，
 * 因为扫描器有独特的诉求：
 *   <ul>
 *     <li>并发调用（对 300+ symbol 并行拉 klines）</li>
 *     <li>自动读取 {@code X-MBX-USED-WEIGHT-1M} 头，主动软限流</li>
 *     <li>429 / 418 时按 {@code Retry-After} 头统一暂停</li>
 *   </ul>
 * 上述保护对策略主链路（`BinanceRestClient` 单次 kline 补齐）来说是过度设计，
 * 但对本模块必须内置。
 * </p>
 * <p>
 * 只在 {@code vertex.quote.volume-surge.enabled=true} 时注册；同一进程内多实例调用会
 * 共用同一个 rate limiter 状态，避免多头分布。
 * </p>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "vertex.quote.volume-surge", name = "enabled", havingValue = "true")
public class BinanceMarketRestClient {

    private static final String DEFAULT_API_URL = "https://api.binance.com";

    private final OkHttpClient httpClient;
    private final String apiUrl;
    private final Semaphore concurrencyGate;

    // ── Rate limit 追踪（进程内共享）────────────────────────────
    /** 最近一次响应携带的 X-MBX-USED-WEIGHT-1M；用于软限流判定 */
    private final AtomicInteger lastUsedWeight1m = new AtomicInteger(0);
    /** 因 429 / 418 或软限触发的暂停结束时刻（epoch ms）；0 表示无暂停 */
    private final AtomicLong pauseUntilMs = new AtomicLong(0L);
    /** 达到即主动 sleep（默认 4000，官方限额 6000 / 分钟）*/
    private final int weightSoftLimit;
    /** 触发软限时的暂停秒数 */
    private final int softLimitPauseSeconds;

    public BinanceMarketRestClient(@Qualifier("quoteOkHttpClient") OkHttpClient httpClient,
                                   VolumeSurgeProperties properties) {
        this.httpClient = httpClient;
        String cfgUrl = properties.getApiUrl();
        this.apiUrl = (cfgUrl == null || cfgUrl.isBlank()) ? DEFAULT_API_URL : cfgUrl;
        int maxConcurrent = Math.max(1, properties.getMaxConcurrentRequests());
        this.concurrencyGate = new Semaphore(maxConcurrent, true);
        this.weightSoftLimit = Math.max(500, properties.getWeightSoftLimit());
        this.softLimitPauseSeconds = Math.max(5, properties.getSoftLimitPauseSeconds());
        log.info("[BinanceMarketRestClient] init: apiUrl={}, maxConcurrent={}, weightSoftLimit={}",
                apiUrl, maxConcurrent, weightSoftLimit);
    }

    // ─── 公开方法 ─────────────────────────────────────────────

    /**
     * 拉全市场 exchangeInfo，返回 {@code symbols} 数组。权重 20。
     */
    public JSONArray getExchangeInfoSymbols() throws IOException {
        String body = doGet(apiUrl + "/api/v3/exchangeInfo");
        JSONObject root = JSON.parseObject(body);
        JSONArray symbols = root.getJSONArray("symbols");
        return symbols == null ? new JSONArray() : symbols;
    }

    /**
     * 拉全市场 24h ticker 快照，一次返回所有 symbol（不传 symbol 参数）。权重 40。
     */
    public JSONArray get24hrTickers() throws IOException {
        String body = doGet(apiUrl + "/api/v3/ticker/24hr");
        return JSON.parseArray(body);
    }

    /**
     * 拉指定 symbol 的 klines（无时间范围，最近 N 根）。权重 2。
     */
    public JSONArray getKlines(String symbol, String interval, int limit) throws IOException {
        return getKlines(symbol, interval, null, null, limit);
    }

    /**
     * 拉指定 symbol 的 klines，可指定时间范围（用于增量补齐）。权重 2。
     * @param symbol    币安 symbol（不含 -）
     * @param interval  Binance 官方 interval code（如 "1h" / "5m"）
     * @param startTime 起始时间 epoch ms（null 表示不限制，返回最近 limit 根）
     * @param endTime   结束时间 epoch ms（null 表示到"现在"）
     * @param limit     最多 1000
     * @return JSONArray，每个元素是 12 元数组：
     *         [openTime, open, high, low, close, volume, closeTime, quoteVolume,
     *          tradeCount, takerBuyBase, takerBuyQuote, ignored]
     */
    public JSONArray getKlines(String symbol, String interval,
                               Long startTime, Long endTime, int limit) throws IOException {
        StringBuilder sb = new StringBuilder(apiUrl.length() + 96)
                .append(apiUrl).append("/api/v3/klines?symbol=").append(symbol)
                .append("&interval=").append(interval)
                .append("&limit=").append(Math.min(Math.max(limit, 1), 1000));
        if (startTime != null) sb.append("&startTime=").append(startTime);
        if (endTime != null) sb.append("&endTime=").append(endTime);
        String body = doGet(sb.toString());
        return JSON.parseArray(body);
    }

    // ─── 内部：带限流保护的 GET ───────────────────────────────

    private String doGet(String url) throws IOException {
        // 1. 软限流暂停检查
        awaitIfPaused();

        // 2. 拿并发许可（Semaphore 保证同时在飞的请求数）
        try {
            concurrencyGate.acquire();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted waiting for concurrency permit", ie);
        }

        try {
            Request req = new Request.Builder()
                    .url(url)
                    .get()
                    .addHeader("Accept", "application/json")
                    .build();

            try (Response resp = httpClient.newCall(req).execute()) {
                // 更新权重追踪
                String used = resp.header("X-MBX-USED-WEIGHT-1M");
                if (used != null && !used.isBlank()) {
                    try {
                        int w = Integer.parseInt(used);
                        lastUsedWeight1m.set(w);
                        if (w >= weightSoftLimit) {
                            long until = System.currentTimeMillis() + softLimitPauseSeconds * 1000L;
                            pauseUntilMs.updateAndGet(prev -> Math.max(prev, until));
                            log.warn("[BinanceMarketRestClient] used weight {} >= soft limit {}, pausing {}s",
                                    w, weightSoftLimit, softLimitPauseSeconds);
                        }
                    } catch (NumberFormatException ignored) {
                        // 头不合法，忽略
                    }
                }

                int code = resp.code();
                if (code == 429 || code == 418) {
                    // Retry-After 头（秒）
                    long retryAfterSec = 30;
                    String ra = resp.header("Retry-After");
                    if (ra != null && !ra.isBlank()) {
                        try {
                            retryAfterSec = Long.parseLong(ra);
                        } catch (NumberFormatException ignored) {
                            // 保持默认
                        }
                    }
                    long until = System.currentTimeMillis() + retryAfterSec * 1000L;
                    pauseUntilMs.updateAndGet(prev -> Math.max(prev, until));
                    log.warn("[BinanceMarketRestClient] {} received, pausing {}s (Retry-After)",
                            code == 418 ? "418 IP-BANNED" : "429 TOO_MANY_REQUESTS", retryAfterSec);
                    throw new IOException("Binance rate limit: HTTP " + code);
                }

                if (!resp.isSuccessful()) {
                    String bodyStr = resp.body() != null ? resp.body().string() : "";
                    throw new IOException("Binance REST HTTP " + code + ": "
                            + truncate(bodyStr, 300));
                }

                if (resp.body() == null) {
                    return "";
                }
                return resp.body().string();
            }
        } finally {
            concurrencyGate.release();
        }
    }

    /** 若有暂停期，阻塞到期后再返回。 */
    private void awaitIfPaused() throws IOException {
        long until = pauseUntilMs.get();
        long now = System.currentTimeMillis();
        if (until > now) {
            long wait = until - now;
            log.info("[BinanceMarketRestClient] paused; sleeping {}ms", wait);
            try {
                Thread.sleep(wait);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted during rate-limit pause", ie);
            }
        }
    }

    // ─── 外部可读的运行时指标（供 Status 页 / 日志用）──────────

    /** 最近一次响应的 X-MBX-USED-WEIGHT-1M；未收到过头为 0 */
    public int getLastUsedWeight1m() {
        return lastUsedWeight1m.get();
    }

    /** 若正处于暂停期，返回剩余毫秒；否则 0 */
    public long getRemainingPauseMs() {
        long until = pauseUntilMs.get();
        long remaining = until - System.currentTimeMillis();
        return Math.max(0, remaining);
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}

package com.vertex.service.quote.scanner;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.vertex.model.entity.quote.KLine;
import com.vertex.model.entity.quote.KLineInterval;
import com.vertex.model.entity.quote.VolumeSurgeConfig;
import com.vertex.service.quote.store.KLineStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 币安现货全站「成交量暴增」扫描器。
 * <p>
 * 主体逻辑：
 * <ol>
 *   <li>Scheduler 每 {@code tickIntervalSeconds}（默认 60s）触发一次心跳</li>
 *   <li>心跳里读 DB {@link VolumeSurgeConfigService#get()}：
 *       {@code enabled=0} → 直接返回；否则检查距上次扫描是否已到 {@code scanIntervalMinutes}</li>
 *   <li>到期时执行完整扫描（24hr ticker + 候选并发 klines + 判定）</li>
 *   <li>触发告警写 RocksDB + 可选 Telegram（读 DB 里的 Telegram 凭据）</li>
 * </ol>
 * 用户在 UI 改 DB config 后最多等一个心跳周期（60s）即生效，无需重启。
 * </p>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "vertex.quote.volume-surge", name = "enabled", havingValue = "true")
public class VolumeSurgeScanner {

    /** 币安现货 scanner 内部固定使用的常量 */
    private static final String EXCHANGE = "binance";
    private static final KLineInterval SCAN_INTERVAL = KLineInterval.H1;
    private static final long ONE_HOUR_MS = 3_600_000L;

    private final VolumeSurgeProperties properties;
    private final VolumeSurgeConfigService configService;
    private final BinanceMarketRestClient rest;
    private final VolumeSurgeStore store;
    /** 复用现有的 K 线持久层做增量缓存 */
    private final KLineStore klineStore;

    /** 可选：未启用 Telegram 时为 null */
    @Autowired(required = false)
    private VolumeSurgeNotifier notifier;

    /** 24h 缓存的 exchangeInfo → onboardDate map（symbol -> onboardDateMs） */
    private Map<String, Long> onboardDateCache = Collections.emptyMap();
    private long onboardDateCacheAt = 0L;

    private ExecutorService worker;
    private final AtomicLong lastScanAt = new AtomicLong(0L);
    private final AtomicInteger lastAlertCount = new AtomicInteger(0);

    public VolumeSurgeScanner(VolumeSurgeProperties properties,
                              VolumeSurgeConfigService configService,
                              BinanceMarketRestClient rest,
                              VolumeSurgeStore store,
                              KLineStore klineStore) {
        this.properties = properties;
        this.configService = configService;
        this.rest = rest;
        this.store = store;
        this.klineStore = klineStore;
    }

    @PostConstruct
    public void init() {
        int poolSize = Math.max(2, properties.getMaxConcurrentRequests());
        ThreadFactory tf = new ThreadFactory() {
            private final AtomicInteger n = new AtomicInteger();
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "volume-surge-worker-" + n.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        };
        this.worker = Executors.newFixedThreadPool(poolSize, tf);
        VolumeSurgeConfig cfg = configService.get();
        log.info("[VolumeSurgeScanner] activated: poolSize={}, tickSec={}, DB.enabled={}, DB.interval={}min, DB.ratio={}x",
                poolSize, properties.getTickIntervalSeconds(),
                yes(cfg.getEnabled()), cfg.getScanIntervalMinutes(), cfg.getSurgeRatioThreshold());
    }

    // ─── 心跳 & 主循环 ─────────────────────────────────────────

    @Scheduled(
            initialDelayString = "${vertex.quote.volume-surge.initial-delay-seconds:60}000",
            fixedDelayString = "${vertex.quote.volume-surge.tick-interval-seconds:60}000"
    )
    public void tick() {
        VolumeSurgeConfig cfg;
        try {
            cfg = configService.get();
        } catch (Exception e) {
            log.warn("[VolumeSurgeScanner] tick: load config failed: {}", e.getMessage());
            return;
        }
        // 1. DB 层业务开关
        if (!yes(cfg.getEnabled())) return;

        // 2. 到期判定：距上次扫描时长 ≥ scanIntervalMinutes 才真的扫
        long intervalMs = Math.max(1, cfg.getScanIntervalMinutes()) * 60_000L;
        long now = System.currentTimeMillis();
        long last = lastScanAt.get();
        if (last > 0 && (now - last) < intervalMs) return;

        long t0 = System.currentTimeMillis();
        try {
            int alerted = doScan(cfg);
            lastAlertCount.set(alerted);
            lastScanAt.set(System.currentTimeMillis());
            log.info("[VolumeSurgeScanner] scan done: alerts={}, elapsed={}ms, usedWeight1m={}",
                    alerted, System.currentTimeMillis() - t0, rest.getLastUsedWeight1m());
        } catch (Throwable t) {
            log.warn("[VolumeSurgeScanner] scan failed: {}", t.getMessage(), t);
        }
    }

    // ─── 核心流程 ─────────────────────────────────────────────

    private int doScan(VolumeSurgeConfig cfg) throws Exception {
        refreshOnboardDateCacheIfNeeded();

        JSONArray tickers = rest.get24hrTickers();
        if (tickers == null || tickers.isEmpty()) {
            log.warn("[VolumeSurgeScanner] 24hr ticker empty");
            return 0;
        }

        String quote = safeString(cfg.getQuoteCurrency(), "USDT").toUpperCase();
        Set<String> blacklist = parseCsvSet(cfg.getSymbolBlacklist());
        Set<String> whitelist = parseCsvSet(cfg.getSymbolWhitelist());
        boolean hasWhitelist = !whitelist.isEmpty();
        int excludeDays = cfg.getExcludeDaysSinceListing() == null ? 0 : cfg.getExcludeDaysSinceListing();
        long now = System.currentTimeMillis();
        long listedCutoffMs = excludeDays > 0
                ? now - (long) excludeDays * 24 * 3600 * 1000
                : Long.MAX_VALUE;

        double min24h = doubleOr(cfg.getMin24hQuoteVolumeUsdt(), 50_000d);
        double max24h = doubleOr(cfg.getMax24hQuoteVolumeUsdt(), 10_000_000d);
        double prefilterPct = doubleOr(cfg.getPrefilterMinAbs24hPriceChangePct(), 0d);

        List<JSONObject> candidates = new ArrayList<>();
        for (int i = 0; i < tickers.size(); i++) {
            JSONObject t = tickers.getJSONObject(i);
            if (t == null) continue;
            String symbol = t.getString("symbol");
            if (symbol == null || !symbol.endsWith(quote)) continue;
            if (blacklist.contains(symbol)) continue;
            if (hasWhitelist && !whitelist.contains(symbol)) continue;

            if (excludeDays > 0) {
                Long onboardMs = onboardDateCache.get(symbol);
                if (onboardMs == null || onboardMs > listedCutoffMs) continue;
            }

            double vol24h = getDouble(t, "quoteVolume");
            if (vol24h < min24h) continue;
            if (vol24h > max24h) continue;

            if (prefilterPct > 0) {
                double chg24h = getDouble(t, "priceChangePercent");
                if (Math.abs(chg24h) < prefilterPct) continue;
            }

            candidates.add(t);
        }

        log.info("[VolumeSurgeScanner] prefilter kept {} of {} tickers", candidates.size(), tickers.size());
        if (candidates.isEmpty()) return 0;

        List<CompletableFuture<VolumeSurgeAlert>> futures = new ArrayList<>(candidates.size());
        for (JSONObject c : candidates) {
            futures.add(CompletableFuture.supplyAsync(() -> evaluateOne(c, cfg), worker));
        }

        int alertCount = 0;
        for (CompletableFuture<VolumeSurgeAlert> f : futures) {
            try {
                VolumeSurgeAlert alert = f.get(120, TimeUnit.SECONDS);
                if (alert != null) {
                    dispatch(alert, cfg);
                    alertCount++;
                }
            } catch (Exception e) {
                log.debug("[VolumeSurgeScanner] evaluate future failed: {}", e.getMessage());
            }
        }
        return alertCount;
    }

    private VolumeSurgeAlert evaluateOne(JSONObject tickerJson, VolumeSurgeConfig cfg) {
        String symbol = tickerJson.getString("symbol");
        try {
            int baselineHours = Math.max(6, cfg.getBaselineHours() == null ? 24 : cfg.getBaselineHours());
            boolean wantUnclosed = yes(cfg.getIncludeUnclosedBar());
            IncrementalFetchResult result = fetchIncrementalKlines(symbol, baselineHours, wantUnclosed);
            if (result == null || result.closedCached.size() < baselineHours) return null;

            List<KLine> closedList = result.closedCached;
            KLine unclosedTail = result.unclosedTail;
            long now = System.currentTimeMillis();

            // ── 选 trigger：优先当前未收盘 bar（若配置开启），否则退回最新已收盘 bar ──
            KLine triggerBar;
            boolean triggeredBeforeClose;
            int elapsedMinutes;
            List<KLine> baselineSource;   // 计算 baseline 用的 K 线段

            if (wantUnclosed && unclosedTail != null
                    && unclosedTail.getOpenTime() != null
                    && (now - unclosedTail.getOpenTime()) < 65L * 60_000L /* 防脏数据 */) {
                triggerBar = unclosedTail;
                triggeredBeforeClose = true;
                elapsedMinutes = (int) Math.min(60,
                        Math.max(0, (now - unclosedTail.getOpenTime()) / 60_000L));
                // baseline = closedList 最后 baselineHours 根（不含 unclosedTail）
                int total = closedList.size();
                int baselineStart = Math.max(0, total - baselineHours);
                baselineSource = closedList.subList(baselineStart, total);
            } else {
                // 只用已收盘 bar：最后一根 closedList 就是 trigger，前面 baselineHours 根做 baseline
                int total = closedList.size();
                if (total < baselineHours + 1) return null;
                triggerBar = closedList.get(total - 1);
                triggeredBeforeClose = false;
                elapsedMinutes = 60;
                int baselineStart = Math.max(0, total - 1 - baselineHours);
                baselineSource = closedList.subList(baselineStart, total - 1);
            }

            if (baselineSource.size() < 6) return null;
            double[] baselineVols = new double[baselineSource.size()];
            for (int i = 0; i < baselineSource.size(); i++) {
                baselineVols[i] = quoteVol(baselineSource.get(i));
            }
            double baselineMedian = median(baselineVols);
            double baselineP90 = percentile(baselineVols, 90);
            double minBaseline = doubleOr(cfg.getMinBaselineMedianUsdt(), 5000d);
            if (baselineMedian < minBaseline) return null;

            long triggerOpenTime = triggerBar.getOpenTime();
            double open = triggerBar.getOpen().doubleValue();
            double close = triggerBar.getClose().doubleValue();
            // ── 核心判据（不做时间缩放）：当前累计 quoteVol / baseline_median ≥ 阈值 ──
            double currentQuoteVol = quoteVol(triggerBar);
            double surgeRatio = currentQuoteVol / baselineMedian;
            double surgeThreshold = doubleOr(cfg.getSurgeRatioThreshold(), 10d);
            if (surgeRatio < surgeThreshold) return null;

            double priceChange1hPct = (close - open) / open * 100.0;
            String direction = priceChange1hPct >= 0 ? "UP" : "DOWN";

            double minPricePct = doubleOr(cfg.getMinPriceChange1hPct(), 0d);
            if (Math.abs(priceChange1hPct) < minPricePct) return null;

            String dir = safeString(cfg.getAlertDirections(), "BOTH");
            if ("UP".equalsIgnoreCase(dir) && !"UP".equals(direction)) return null;
            if ("DOWN".equalsIgnoreCase(dir) && !"DOWN".equals(direction)) return null;

            if (store.getCooldownEnd("binance", symbol) > System.currentTimeMillis()) return null;

            return VolumeSurgeAlert.builder()
                    .exchange("binance")
                    .symbol(symbol)
                    .direction(direction)
                    .alertedAt(System.currentTimeMillis())
                    .triggerBarOpenTime(triggerOpenTime)
                    .surgeRatio(round(surgeRatio, 2))
                    .current1hQuoteUsdt(round(currentQuoteVol, 2))
                    .baselineMedianUsdt(round(baselineMedian, 2))
                    .baselineP90Usdt(round(baselineP90, 2))
                    .openPrice(open)
                    .closePrice(close)
                    .priceChange1hPct(round(priceChange1hPct, 4))
                    .vol24hUsdt(round(getDouble(tickerJson, "quoteVolume"), 2))
                    .priceChange24hPct(round(getDouble(tickerJson, "priceChangePercent"), 4))
                    .triggeredBeforeClose(triggeredBeforeClose)
                    .elapsedMinutes(elapsedMinutes)
                    .build();
        } catch (Throwable t) {
            log.debug("[VolumeSurgeScanner] evaluate {} failed: {}", symbol, t.getMessage());
            return null;
        }
    }

    /** 增量 K 线的返回结果：已收盘缓存 + 可选的当前未收盘 bar */
    private static class IncrementalFetchResult {
        final List<KLine> closedCached;
        final KLine unclosedTail;  // 可为 null
        IncrementalFetchResult(List<KLine> closedCached, KLine unclosedTail) {
            this.closedCached = closedCached;
            this.unclosedTail = unclosedTail;
        }
    }

    private void dispatch(VolumeSurgeAlert alert, VolumeSurgeConfig cfg) {
        store.saveAlert(alert);
        int cooldownH = cfg.getCooldownHours() == null ? 6 : cfg.getCooldownHours();
        long cdEnd = alert.getAlertedAt() + cooldownH * 3600L * 1000L;
        store.setCooldownEnd(alert.getExchange(), alert.getSymbol(), cdEnd);
        log.info("[VolumeSurgeScanner] ALERT {} ratio={}x price1h={}% ({})",
                alert.getSymbol(), alert.getSurgeRatio(),
                alert.getPriceChange1hPct(), alert.getDirection());
        if (notifier != null && yes(cfg.getTelegramEnabled())) {
            try {
                notifier.sendAlert(alert,
                        "https://api.telegram.org",
                        cfg.getTelegramBotToken(),
                        cfg.getTelegramChatId());
            } catch (Exception e) {
                log.warn("[VolumeSurgeScanner] send TG failed: {}", e.getMessage());
            }
        }
    }

    // ─── K 线增量缓存（核心优化）─────────────────────────────

    /**
     * 增量拉 K 线：优先走 RocksDB（KLineStore）缓存；只有当"最新已缓存 K 线"过老
     * （> 1 根 K 线周期）或者根本没缓存时才打 REST，且只拉缺口部分。
     * <p>
     * 返回结构：
     * <ul>
     *   <li>{@code closedCached}: 按 openTime 升序、总长 ≥ baselineHours+1 的已收盘 K 线</li>
     *   <li>{@code unclosedTail}: 当前正在跑（未收盘）的 1H K 线，仅当 {@code wantUnclosed=true}
     *       且能拉到时返回；否则为 null</li>
     * </ul>
     * </p>
     * <p>
     * 未收盘 bar 不写进 KLineStore（会被策略 WS 覆盖，且它每分钟都变），只在返回值里带回。
     * </p>
     */
    private IncrementalFetchResult fetchIncrementalKlines(String symbol, int baselineHours,
                                                          boolean wantUnclosed) throws Exception {
        long now = System.currentTimeMillis();
        int desired = baselineHours + 2;
        long windowStartMs = now - (desired + 2) * ONE_HOUR_MS;

        // 1. 从 KLineStore 读缓存（已收盘）
        List<KLine> cached = klineStore.query(
                EXCHANGE, symbol, SCAN_INTERVAL,
                windowStartMs, now + ONE_HOUR_MS,
                200, true /* ascending */);
        long cursorTime = store.getCachedCursor(EXCHANGE, symbol);

        long cachedNewestOpen = cached.isEmpty() ? 0L : cached.get(cached.size() - 1).getOpenTime();
        long effectiveNewest = Math.max(cursorTime, cachedNewestOpen);
        long currentHourOpen = now - (now % ONE_HOUR_MS);

        boolean needFetch;
        Long restStartTime;
        int restLimit;

        if (cached.size() < baselineHours || effectiveNewest == 0L) {
            // 冷启动 / 缓存完全不够：拉 desired 根
            needFetch = true;
            restStartTime = null;
            restLimit = desired;
        } else if (currentHourOpen > effectiveNewest) {
            // 有缓存但缺 N 根已收盘 bar
            needFetch = true;
            restStartTime = effectiveNewest + ONE_HOUR_MS;
            long gap = (currentHourOpen - effectiveNewest) / ONE_HOUR_MS;
            restLimit = (int) Math.min(gap + 1, 100);
        } else if (wantUnclosed) {
            // 缓存的已收盘部分足够新，但我们还需要当前未收盘 bar → 只拉 1 根
            needFetch = true;
            restStartTime = null;   // 让 Binance 返回最新 N 根
            restLimit = 1;
        } else {
            needFetch = false;
            restStartTime = null;
            restLimit = 0;
        }

        KLine unclosedTail = null;
        if (needFetch) {
            JSONArray raw = rest.getKlines(symbol, "1h", restStartTime, null, restLimit);
            List<KLine> fresh = BinanceKlineRowConverter.convert(
                    EXCHANGE, symbol, SCAN_INTERVAL, now, raw);

            // 已收盘 bar：写进 KLineStore（覆盖式，因为收盘后数据稳定）
            List<KLine> closedOnly = new ArrayList<>(fresh.size());
            long newCursor = 0L;
            for (KLine k : fresh) {
                if (k.getOpenTime() == null) continue;
                if (Boolean.TRUE.equals(k.getClosed())) {
                    closedOnly.add(k);
                    if (k.getOpenTime() > newCursor) newCursor = k.getOpenTime();
                } else {
                    // 只保留 openTime 最大的那一根作为 unclosed tail
                    if (unclosedTail == null
                            || (unclosedTail.getOpenTime() != null
                                && k.getOpenTime() > unclosedTail.getOpenTime())) {
                        unclosedTail = k;
                    }
                }
            }
            if (!closedOnly.isEmpty()) {
                klineStore.saveBatch(closedOnly);
                if (newCursor > effectiveNewest) {
                    store.setCachedCursor(EXCHANGE, symbol, newCursor);
                }
                cached = klineStore.query(
                        EXCHANGE, symbol, SCAN_INTERVAL,
                        windowStartMs, now + ONE_HOUR_MS,
                        200, true);
            }
        }
        return new IncrementalFetchResult(cached, wantUnclosed ? unclosedTail : null);
    }

    private static double quoteVol(KLine k) {
        if (k == null || k.getQuoteVolume() == null) return 0d;
        return k.getQuoteVolume().doubleValue();
    }

    // ─── exchangeInfo 缓存（RocksDB 持久化 + 内存热缓存）─────

    private void refreshOnboardDateCacheIfNeeded() throws Exception {
        long now = System.currentTimeMillis();
        long ttlMs = 24L * 3600L * 1000L;

        // 内存缓存命中直接返回
        if (!onboardDateCache.isEmpty() && (now - onboardDateCacheAt) < ttlMs) return;

        // 冷启动：先尝试 RocksDB 兜底，避免每次重启都打 REST
        if (onboardDateCache.isEmpty()) {
            VolumeSurgeStore.ExchangeInfoCache disk = store.loadExchangeInfo();
            if (disk != null && (now - disk.refreshedAt()) < ttlMs) {
                Map<String, Long> loaded = deserializeOnboardMap(disk.jsonString());
                if (!loaded.isEmpty()) {
                    this.onboardDateCache = loaded;
                    this.onboardDateCacheAt = disk.refreshedAt();
                    log.info("[VolumeSurgeScanner] exchangeInfo loaded from RocksDB: {} symbols (refreshed {}m ago)",
                            loaded.size(), (now - disk.refreshedAt()) / 60_000);
                    return;
                }
            }
        }

        // REST 拉全量
        JSONArray symbols = rest.getExchangeInfoSymbols();
        java.util.HashMap<String, Long> map = new java.util.HashMap<>(symbols.size() * 2);
        for (int i = 0; i < symbols.size(); i++) {
            JSONObject s = symbols.getJSONObject(i);
            if (s == null) continue;
            String sym = s.getString("symbol");
            if (sym == null) continue;
            String status = s.getString("status");
            if (!"TRADING".equals(status)) continue;
            Long onboard = s.getLong("onboardDate");
            map.put(sym, onboard == null ? 0L : onboard);
        }
        this.onboardDateCache = map;
        this.onboardDateCacheAt = now;

        // 持久化到 RocksDB
        try {
            String json = JSON.toJSONString(map);
            store.saveExchangeInfo(json, now);
        } catch (Exception e) {
            log.warn("[VolumeSurgeScanner] persist exchangeInfo failed: {}", e.getMessage());
        }
        log.info("[VolumeSurgeScanner] exchangeInfo refreshed via REST: {} TRADING symbols", map.size());
    }

    private static Map<String, Long> deserializeOnboardMap(String json) {
        try {
            JSONObject o = JSON.parseObject(json);
            java.util.HashMap<String, Long> out = new java.util.HashMap<>(o.size() * 2);
            for (Map.Entry<String, Object> e : o.entrySet()) {
                if (e.getValue() == null) continue;
                try {
                    out.put(e.getKey(), Long.parseLong(e.getValue().toString()));
                } catch (NumberFormatException ignored) {
                    // 跳过
                }
            }
            return out;
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }

    // ─── 数值 / 类型工具 ──────────────────────────────────────

    private static double median(double[] arr) {
        double[] copy = arr.clone();
        Arrays.sort(copy);
        int n = copy.length;
        return n % 2 == 0 ? (copy[n / 2 - 1] + copy[n / 2]) / 2.0 : copy[n / 2];
    }

    private static double percentile(double[] arr, double p) {
        double[] copy = arr.clone();
        Arrays.sort(copy);
        int idx = (int) Math.ceil(p / 100.0 * copy.length) - 1;
        idx = Math.min(Math.max(idx, 0), copy.length - 1);
        return copy[idx];
    }

    private static double round(double v, int scale) {
        return BigDecimal.valueOf(v).setScale(scale, java.math.RoundingMode.HALF_UP).doubleValue();
    }

    private static double getDouble(JSONObject o, String key) {
        Object v = o.get(key);
        if (v == null) return 0d;
        try {
            return new BigDecimal(v.toString()).doubleValue();
        } catch (Exception e) {
            return 0d;
        }
    }

    private static double doubleOr(BigDecimal bd, double fallback) {
        return bd == null ? fallback : bd.doubleValue();
    }

    private static String safeString(String s, String fallback) {
        return (s == null || s.isBlank()) ? fallback : s;
    }

    private static boolean yes(Integer v) {
        return v != null && v == 1;
    }

    private static Set<String> parseCsvSet(String csv) {
        if (csv == null || csv.isBlank()) return new HashSet<>();
        Set<String> out = new HashSet<>();
        for (String s : csv.split(",")) {
            String t = s.trim().toUpperCase();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    // ─── 供 status 用 ────────────────────────────────────────

    public long getLastScanAt() { return lastScanAt.get(); }
    public int getLastAlertCount() { return lastAlertCount.get(); }
}

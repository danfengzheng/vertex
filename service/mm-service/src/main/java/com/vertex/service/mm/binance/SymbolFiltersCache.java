package com.vertex.service.mm.binance;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.vertex.service.mm.config.MmProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * exchangeInfo 拉取 + 缓存所有 symbol 的 filter 规则。
 * <p>
 * 启动时全量拉一次；每 24h 后台刷新一次；用户 subscribe 新 symbol 时 lazy 触发 refresh。
 * </p>
 * <p>
 * <b>Paper 模式也应用规则</b> —— 保证 Paper 挂单和 Live 一样受 tick/step 约束，
 * 这样 Paper 观察到的结果才和 Live 一致（否则 Paper 挂 3025.123 全填，切 Live 全拒）。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SymbolFiltersCache {

    private final MmProperties properties;

    private final Map<String, SymbolFilters> cache = new ConcurrentHashMap<>();

    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build();

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1,
            r -> { Thread t = new Thread(r, "mm-symbol-filters"); t.setDaemon(true); return t; });

    @PostConstruct
    void init() {
        // 启动时异步拉一次，失败不阻塞
        scheduler.schedule(this::refreshAll, 3, TimeUnit.SECONDS);
        // 每 24h 刷新
        scheduler.scheduleWithFixedDelay(this::refreshAll, 24, 24, TimeUnit.HOURS);
    }

    /**
     * 拿 symbol 的 filter；如果没缓存，同步 refresh 一次再取。
     * @return SymbolFilters；若拉取失败返回 null（调用方需容忍 null）
     */
    public SymbolFilters get(String symbol) {
        if (symbol == null) return null;
        String upper = symbol.toUpperCase();
        SymbolFilters f = cache.get(upper);
        if (f != null) return f;
        // 缓存未命中：同步 refresh
        try {
            refreshAll();
        } catch (Exception ex) {
            log.warn("[SymbolFilters] refresh on miss failed: {}", ex.getMessage());
        }
        return cache.get(upper);
    }

    /**
     * 便捷 round —— 找不到规则时直接返回原值（避免上层复杂 null 分支）
     */
    public BigDecimal roundPrice(String symbol, BigDecimal price, String side) {
        SymbolFilters f = get(symbol);
        return f == null ? price : f.roundPrice(price, side);
    }

    public BigDecimal roundQty(String symbol, BigDecimal qty) {
        SymbolFilters f = get(symbol);
        return f == null ? qty : f.roundQty(qty);
    }

    private synchronized void refreshAll() {
        String url = properties.getBinanceUsdcmBaseUrl() + "/fapi/v1/exchangeInfo";
        try {
            Request req = new Request.Builder().url(url).get().build();
            try (Response resp = http.newCall(req).execute()) {
                if (!resp.isSuccessful() || resp.body() == null) {
                    log.warn("[SymbolFilters] exchangeInfo HTTP {} — cache unchanged", resp.code());
                    return;
                }
                String body = resp.body().string();
                JSONObject j = JSON.parseObject(body);
                JSONArray symbols = j.getJSONArray("symbols");
                if (symbols == null) return;
                int parsed = 0;
                for (int i = 0; i < symbols.size(); i++) {
                    SymbolFilters f = parseOne(symbols.getJSONObject(i));
                    if (f != null) {
                        cache.put(f.getSymbol(), f);
                        parsed++;
                    }
                }
                log.info("[SymbolFilters] refreshed {} symbols from exchangeInfo", parsed);
            }
        } catch (Exception ex) {
            log.error("[SymbolFilters] refresh failed: {}", ex.getMessage());
        }
    }

    private SymbolFilters parseOne(JSONObject sym) {
        try {
            String symbol = sym.getString("symbol");
            if (symbol == null) return null;

            BigDecimal tickSize = null, stepSize = null, minQty = null, minNotional = null;
            JSONArray filters = sym.getJSONArray("filters");
            if (filters == null) return null;
            for (int k = 0; k < filters.size(); k++) {
                JSONObject f = filters.getJSONObject(k);
                String type = f.getString("filterType");
                if ("PRICE_FILTER".equals(type)) {
                    tickSize = new BigDecimal(f.getString("tickSize"));
                } else if ("LOT_SIZE".equals(type)) {
                    stepSize = new BigDecimal(f.getString("stepSize"));
                    minQty = new BigDecimal(f.getString("minQty"));
                } else if ("MIN_NOTIONAL".equals(type)) {
                    // futures 有的用 "notional" 字段，有的用 "minNotional"
                    String n = f.containsKey("notional") ? f.getString("notional") : f.getString("minNotional");
                    if (n != null) minNotional = new BigDecimal(n);
                }
            }
            if (tickSize == null || stepSize == null) return null;

            int pricePrecision = sym.containsKey("pricePrecision")
                    ? sym.getIntValue("pricePrecision") : precisionOf(tickSize);
            int qtyPrecision = sym.containsKey("quantityPrecision")
                    ? sym.getIntValue("quantityPrecision") : precisionOf(stepSize);

            return SymbolFilters.builder()
                    .symbol(symbol.toUpperCase())
                    .tickSize(tickSize)
                    .stepSize(stepSize)
                    .minQty(minQty)
                    .minNotional(minNotional)
                    .pricePrecision(pricePrecision)
                    .qtyPrecision(qtyPrecision)
                    .build();
        } catch (Exception ex) {
            log.debug("[SymbolFilters] parseOne error: {}", ex.getMessage());
            return null;
        }
    }

    /** 从 tickSize/stepSize 推小数位数：0.01 → 2，0.001 → 3 */
    private static int precisionOf(BigDecimal v) {
        if (v == null) return 8;
        int scale = Math.max(0, v.stripTrailingZeros().scale());
        return Math.min(scale, 12);
    }
}

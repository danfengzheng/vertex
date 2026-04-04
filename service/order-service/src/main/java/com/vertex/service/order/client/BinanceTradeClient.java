package com.vertex.service.order.client;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.vertex.common.core.GlobalError;
import com.vertex.common.core.exception.BizException;
import com.vertex.model.entity.trading.TrdExchangeSymbol;
import com.vertex.service.order.cache.ExchangeSymbolCache;
import com.vertex.service.order.config.TradingProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 币安现货交易 REST API 客户端
 * <p>
 * 支持下单、撤单、查询订单、查询账户余额。
 * 所有请求使用 HMAC-SHA256 签名。
 * <p>
 * 下单前自动对齐 LOT_SIZE：
 * 通过 exchangeInfo 查询交易对的 stepSize，对数量做向下截断，
 * 避免 Binance LOT_SIZE 过滤器拒单。stepSize 结果在内存中缓存，应用生命周期内不重复查询。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BinanceTradeClient {

    private final OkHttpClient httpClient;
    private final TradingProperties properties;
    private final ExchangeSymbolCache exchangeSymbolCache;

    @EventListener(ApplicationReadyEvent.class)
    public void loadStep() {
        List<String> symbols = new ArrayList<>();
        symbols.add("BTCUSDT");
        symbols.add("ETHUSDT");
        symbols.add("LTCUSDT");
        symbols.add("BNBUSDT");
        this.preloadStepSizes(symbols);
    }
    /**
     * 交易对 stepSize 缓存（binanceSymbol → stepSize）。
     * <p>
     * 缓存时机：
     * <ul>
     *   <li><b>懒加载（默认）</b>：首次对某交易对调用 {@link #placeOrder} 时，
     *       触发一次 exchangeInfo HTTP 请求，结果写入缓存，后续永不重复查询。</li>
     *   <li><b>主动预热</b>：可在启动时调用 {@link #preloadStepSizes} 批量预热，
     *       消除首单额外延迟。</li>
     * </ul>
     * 缓存在 JVM 进程内永久有效（重启后重新加载）。
     * Binance 极少修改 stepSize，无需 TTL 过期。
     */
    private final ConcurrentHashMap<String, BigDecimal> stepSizeCache = new ConcurrentHashMap<>();
    /** 交易对 tickSize 缓存（binanceSymbol → tickSize，用于 LIMIT 价格对齐） */
    private final ConcurrentHashMap<String, BigDecimal> tickSizeCache = new ConcurrentHashMap<>();

    /**
     * 下单
     * <p>
     * 下单前会自动根据交易对的 LOT_SIZE stepSize 对数量做向下截断对齐：
     * 例如 ETHUSDT stepSize=0.0001，3.77978036 → 3.7797
     *
     * @param apiKey    解密后的 API Key
     * @param apiSecret 解密后的 API Secret
     * @param symbol    交易对（BTC-USDT 格式）
     * @param side      BUY / SELL
     * @param type      MARKET / LIMIT
     * @param quantity  数量（会自动按 stepSize 向下截断）
     * @param price     价格（LIMIT 订单必填，MARKET 可为 null）
     * @return 交易所返回的订单信息
     */
    public JSONObject placeOrder(String apiKey, String apiSecret, String symbol,
                                 String side, String type, BigDecimal quantity, BigDecimal price) {
        String binanceSymbol = toBinanceSymbol(symbol);

        // ── 按 LOT_SIZE stepSize 向下截断数量 ──────────────────
        BigDecimal alignedQty = alignToStepSize(binanceSymbol, quantity);
        if (alignedQty.compareTo(BigDecimal.ZERO) <= 0) {
            log.error("[Binance Trade] quantity {} is less than stepSize for {}, cannot place order",
                    quantity, binanceSymbol);
            throw new BizException(GlobalError.TRADE_API_ERROR);
        }
        if (alignedQty.compareTo(quantity) != 0) {
            log.info("[Binance Trade] Quantity aligned by LOT_SIZE: {} → {} ({})",
                    quantity.toPlainString(), alignedQty.toPlainString(), binanceSymbol);
        }

        // LIMIT 订单：按 PRICE_FILTER tickSize 向下截断价格（避免 Binance 精度拒单）
        BigDecimal alignedPrice = price;
        if ("LIMIT".equals(type) && price != null && price.compareTo(BigDecimal.ZERO) > 0) {
            alignedPrice = alignToTickSize(binanceSymbol, price);
            if (alignedPrice.compareTo(price) != 0) {
                log.info("[Binance Trade] Price aligned by PRICE_FILTER: {} → {} ({})",
                        price, alignedPrice, binanceSymbol);
            }
        }

        StringBuilder params = new StringBuilder();
        params.append("symbol=").append(binanceSymbol);
        params.append("&side=").append(side);
        params.append("&type=").append(type);
        params.append("&quantity=").append(alignedQty.stripTrailingZeros().toPlainString());
        if ("LIMIT".equals(type) && alignedPrice != null) {
            params.append("&timeInForce=GTC");
            params.append("&price=").append(alignedPrice.stripTrailingZeros().toPlainString());
        }
        params.append("&recvWindow=").append(properties.getBinance().getRecvWindow());
        params.append("&timestamp=").append(System.currentTimeMillis());

        String signature = sign(params.toString(), apiSecret);
        params.append("&signature=").append(signature);

        String url = properties.getBinance().getApiUrl() + "/api/v3/order";
        RequestBody body = RequestBody.create(params.toString(), MediaType.parse("application/x-www-form-urlencoded"));
        Request request = new Request.Builder()
                .url(url)
                .post(body)
                .addHeader("X-MBX-APIKEY", apiKey)
                .build();

        return executeRequest(request, "placeOrder");
    }

    /**
     * 撤单
     */
    public JSONObject cancelOrder(String apiKey, String apiSecret, String symbol, String orderId) {
        String binanceSymbol = toBinanceSymbol(symbol);
        StringBuilder params = new StringBuilder();
        params.append("symbol=").append(binanceSymbol);
        params.append("&orderId=").append(orderId);
        params.append("&recvWindow=").append(properties.getBinance().getRecvWindow());
        params.append("&timestamp=").append(System.currentTimeMillis());

        String signature = sign(params.toString(), apiSecret);
        params.append("&signature=").append(signature);

        String url = properties.getBinance().getApiUrl() + "/api/v3/order?" + params;
        Request request = new Request.Builder()
                .url(url)
                .delete()
                .addHeader("X-MBX-APIKEY", apiKey)
                .build();

        return executeRequest(request, "cancelOrder");
    }

    /**
     * 查询订单状态
     */
    public JSONObject queryOrder(String apiKey, String apiSecret, String symbol, String orderId) {
        String binanceSymbol = toBinanceSymbol(symbol);
        StringBuilder params = new StringBuilder();
        params.append("symbol=").append(binanceSymbol);
        params.append("&orderId=").append(orderId);
        params.append("&recvWindow=").append(properties.getBinance().getRecvWindow());
        params.append("&timestamp=").append(System.currentTimeMillis());

        String signature = sign(params.toString(), apiSecret);
        params.append("&signature=").append(signature);

        String url = properties.getBinance().getApiUrl() + "/api/v3/order?" + params;
        Request request = new Request.Builder()
                .url(url)
                .get()
                .addHeader("X-MBX-APIKEY", apiKey)
                .build();

        return executeRequest(request, "queryOrder");
    }

    /**
     * 查询账户余额
     */
    public JSONObject getAccount(String apiKey, String apiSecret) {
        StringBuilder params = new StringBuilder();
        params.append("recvWindow=").append(properties.getBinance().getRecvWindow());
        params.append("&timestamp=").append(System.currentTimeMillis());

        String signature = sign(params.toString(), apiSecret);
        params.append("&signature=").append(signature);

        String url = properties.getBinance().getApiUrl() + "/api/v3/account?" + params;
        Request request = new Request.Builder()
                .url(url)
                .get()
                .addHeader("X-MBX-APIKEY", apiKey)
                .build();

        return executeRequest(request, "getAccount");
    }

    /**
     * 测试连通性（使用 account 接口验证 API Key 有效性）
     */
    public boolean testConnection(String apiKey, String apiSecret) {
        try {
            JSONObject account = getAccount(apiKey, apiSecret);
            return account != null && account.containsKey("balances");
        } catch (Exception e) {
            log.warn("Connection test failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 主动预热 stepSize 缓存（可在启动时调用，消除首单额外网络延迟）。
     * <p>
     * 已缓存的交易对会被跳过，重复调用安全。
     *
     * <pre>
     * 调用示例（在 @PostConstruct 或 ApplicationRunner 中）：
     *   tradeClient.preloadStepSizes(List.of("ETHUSDT", "BTCUSDT", "SOLUSDT"));
     * </pre>
     *
     * @param symbols Binance 格式的交易对列表（如 "ETHUSDT"）
     */
    public void preloadStepSizes(java.util.List<String> symbols) {
        if (symbols == null || symbols.isEmpty()) return;
        log.info("[exchangeInfo] Preloading filters for {} symbols: {}", symbols.size(), symbols);
        symbols.forEach(s -> {
            String binanceSymbol = toBinanceSymbol(s);
            if (!stepSizeCache.containsKey(binanceSymbol)) {
                fetchAndCacheFilters(binanceSymbol);
            }
        });
        log.info("[exchangeInfo] Preload done. stepSizeCache={}, tickSizeCache={}",
                stepSizeCache.size(), tickSizeCache.size());
    }

    // ─── LOT_SIZE 对齐 ──────────────────────────────────────

    /**
     * 按交易对 LOT_SIZE stepSize 向下截断数量。
     * <p>
     * 算法：floor(quantity / stepSize) * stepSize
     * 例：quantity=3.77978036, stepSize=0.0001 → floor(37797.8036) * 0.0001 = 3.7797
     * <p>
     * stepSize 从 Binance {@code /api/v3/exchangeInfo} 查询并缓存。
     * 若查询失败则降级使用 8 位小数截断（保守兜底，极端情况下仍可能被拒绝）。
     */
    private BigDecimal alignToStepSize(String binanceSymbol, BigDecimal quantity) {
        BigDecimal stepSize = getStepSize(binanceSymbol);
        if (stepSize == null || stepSize.compareTo(BigDecimal.ZERO) <= 0) {
            // 查询失败：降级截断到 8 位（保留原逻辑，不影响功能）
            log.warn("[LOT_SIZE] Cannot get stepSize for {}, using 8 decimal fallback", binanceSymbol);
            return quantity.setScale(8, RoundingMode.DOWN);
        }
        // floor(quantity / stepSize) * stepSize
        BigDecimal steps = quantity.divide(stepSize, 0, RoundingMode.DOWN);
        return steps.multiply(stepSize).stripTrailingZeros();
    }

    /**
     * 按交易对 PRICE_FILTER tickSize 向下截断 LIMIT 价格。
     * <p>
     * 算法：floor(price / tickSize) * tickSize
     * 若查询失败则返回原价格（不阻断下单，Binance 如拒绝将在 executeRequest 中抛出）。
     */
    private BigDecimal alignToTickSize(String binanceSymbol, BigDecimal price) {
        BigDecimal tickSize = tickSizeCache.get(binanceSymbol);
        if (tickSize == null) {
            // 独立查询 ExchangeSymbolCache，不依赖 getStepSize 的副作用
            // （getStepSize 会顺带填充 tickSizeCache，但若 alignToTickSize 单独调用则无该副作用）
            TrdExchangeSymbol esym = exchangeSymbolCache.getExchangeSymbol("binance", "SPOT", binanceSymbol);
            if (esym != null && esym.getTickSize() != null && esym.getTickSize().compareTo(BigDecimal.ZERO) > 0) {
                tickSize = esym.getTickSize();
                tickSizeCache.put(binanceSymbol, tickSize);
            }
        }
        if (tickSize == null) {
            // 仍未命中（ExchangeSymbolCache 也无数据）：回退到 exchangeInfo API 调用
            fetchAndCacheFilters(binanceSymbol);
            tickSize = tickSizeCache.get(binanceSymbol);
        }
        if (tickSize == null || tickSize.compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("[PRICE_FILTER] Cannot get tickSize for {}, using price as-is", binanceSymbol);
            return price;
        }
        BigDecimal ticks = price.divide(tickSize, 0, RoundingMode.DOWN);
        return ticks.multiply(tickSize).stripTrailingZeros();
    }

    /**
     * 查询并缓存交易对的 LOT_SIZE stepSize（公开接口，无需 API Key）。
     */
    public BigDecimal getStepSize(String binanceSymbol) {
        BigDecimal cached = stepSizeCache.get(binanceSymbol);
        if (cached != null) return cached;
        // 优先从 ExchangeSymbolCache（DB 同步数据）获取，避免下单路径上的阻塞 HTTP 调用
        TrdExchangeSymbol esym = exchangeSymbolCache.getExchangeSymbol("binance", "SPOT", binanceSymbol);
        if (esym != null && esym.getLotSize() != null && esym.getLotSize().compareTo(BigDecimal.ZERO) > 0) {
            stepSizeCache.put(binanceSymbol, esym.getLotSize());
            // tickSize 一并填充，减少后续 alignToTickSize 再走一次 API
            if (esym.getTickSize() != null && esym.getTickSize().compareTo(BigDecimal.ZERO) > 0) {
                tickSizeCache.put(binanceSymbol, esym.getTickSize());
            }
            log.debug("[Trade] stepSize/tickSize loaded from ExchangeSymbolCache for {}", binanceSymbol);
            return esym.getLotSize();
        }
        // 兜底：ExchangeSymbolCache 未命中（首次部署 / 表为空）时，回退到 exchangeInfo API 调用
        fetchAndCacheFilters(binanceSymbol);
        return stepSizeCache.get(binanceSymbol);
    }

    /**
     * 一次 exchangeInfo 请求同时缓存 LOT_SIZE(stepSize) 和 PRICE_FILTER(tickSize)。
     * <p>
     * 响应示例（部分）：
     * <pre>
     * {
     *   "filters": [
     *     { "filterType": "LOT_SIZE",    "stepSize": "0.00010000" },
     *     { "filterType": "PRICE_FILTER","tickSize":  "0.01000000" }
     *   ]
     * }
     * </pre>
     */
    private void fetchAndCacheFilters(String binanceSymbol) {
        try {
            String url = properties.getBinance().getApiUrl()
                    + "/api/v3/exchangeInfo?symbol=" + binanceSymbol;
            Request request = new Request.Builder().url(url).get().build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    log.warn("[exchangeInfo] request failed for {}: {}", binanceSymbol, response.code());
                    return;
                }
                ResponseBody rb = response.body();
                if (rb == null) return;

                JSONObject body = JSON.parseObject(rb.string());
                JSONArray symbols = body.getJSONArray("symbols");
                if (symbols == null || symbols.isEmpty()) return;

                JSONObject symbolInfo = symbols.getJSONObject(0);
                JSONArray filters = symbolInfo.getJSONArray("filters");
                if (filters == null) return;

                for (int i = 0; i < filters.size(); i++) {
                    JSONObject filter = filters.getJSONObject(i);
                    String filterType = filter.getString("filterType");
                    if ("LOT_SIZE".equals(filterType)) {
                        String stepStr = filter.getString("stepSize");
                        if (stepStr != null) {
                            BigDecimal step = new BigDecimal(stepStr);
                            stepSizeCache.put(binanceSymbol, step);
                            log.info("[LOT_SIZE] {} stepSize={}", binanceSymbol, step.toPlainString());
                        }
                    } else if ("PRICE_FILTER".equals(filterType)) {
                        String tickStr = filter.getString("tickSize");
                        if (tickStr != null) {
                            BigDecimal tick = new BigDecimal(tickStr);
                            tickSizeCache.put(binanceSymbol, tick);
                            log.info("[PRICE_FILTER] {} tickSize={}", binanceSymbol, tick.toPlainString());
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[exchangeInfo] Failed to fetch filters for {}: {}", binanceSymbol, e.getMessage());
        }
    }

    // ─── 内部方法 ──────────────────────────────────────────

    private JSONObject executeRequest(Request request, String operation) {
        try (Response response = httpClient.newCall(request).execute()) {
            ResponseBody responseBody = response.body();
            String bodyStr = responseBody != null ? responseBody.string() : "";

            if (!response.isSuccessful()) {
                log.error("[Binance Trade] {} failed, code: {}, body: {}", operation, response.code(), bodyStr);
                throw new BizException(GlobalError.TRADE_API_ERROR);
            }

            return JSON.parseObject(bodyStr);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("[Binance Trade] {} error: {}", operation, e.getMessage(), e);
            throw new BizException(GlobalError.TRADE_API_ERROR);
        }
    }

    /**
     * HMAC-SHA256 签名
     */
    private String sign(String data, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new BizException(GlobalError.TRADE_API_ERROR);
        }
    }

    /**
     * BTC-USDT → BTCUSDT
     */
    private String toBinanceSymbol(String symbol) {
        return symbol.replace("-", "").toUpperCase();
    }
}

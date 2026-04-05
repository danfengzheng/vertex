package com.vertex.service.order.client;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.vertex.common.core.GlobalError;
import com.vertex.common.core.exception.BizException;
import com.vertex.model.entity.trading.MarginType;
import com.vertex.model.entity.trading.MarketType;
import com.vertex.model.entity.trading.TrdExchangeSymbol;
import com.vertex.service.order.cache.ExchangeSymbolCache;
import com.vertex.service.order.config.TradingProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 币安合约交易 REST API 客户端
 * <p>
 * 支持 USDM（fapi.binance.com）和 COINM（dapi.binance.com）两类合约。
 * <p>
 * 功能：下单（含 reduceOnly 平仓）、设置杠杆/保证金模式、查询持仓风险、标记价格、余额。
 * 所有私有请求使用 HMAC-SHA256 签名。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BinanceFuturesClient {

    private final OkHttpClient httpClient;
    private final TradingProperties properties;
    private final ExchangeSymbolCache exchangeSymbolCache;

    /** USDM 合约 Base URL */
    private static final String USDM_BASE = "https://fapi.binance.com";
    /** COINM 合约 Base URL */
    private static final String COINM_BASE = "https://dapi.binance.com";

    /** 交易对 stepSize 缓存（"USDM:BTCUSDT" → stepSize） */
    private final ConcurrentHashMap<String, BigDecimal> stepSizeCache = new ConcurrentHashMap<>();
    /** 交易对 tickSize 缓存（"USDM:BTCUSDT" → tickSize，用于 LIMIT 价格对齐） */
    private final ConcurrentHashMap<String, BigDecimal> tickSizeCache = new ConcurrentHashMap<>();
    /** 账户持仓模式缓存（"apiKey:USDM" → true=hedge/false=one-way） */
    private final ConcurrentHashMap<String, Boolean> hedgeModeCache = new ConcurrentHashMap<>();

    // ─── 公开方法 ──────────────────────────────────────────

    /**
     * 合约下单
     *
     * @param apiKey     解密后的 API Key
     * @param apiSecret  解密后的 API Secret
     * @param symbol     交易对（如 "BTCUSDT" 或 "BTC-USDT"）
     * @param side       BUY / SELL
     * @param type       MARKET / LIMIT
     * @param quantity   下单数量（自动按 stepSize 向下截断）
     * @param price      价格（LIMIT 必填，MARKET 为 null）
     * @param reduceOnly 是否仅平仓（true = 强制 reduceOnly，用于平仓防止翻仓）
     * @param marketType USDM / COINM
     */
    public JSONObject placeOrder(String apiKey, String apiSecret, String symbol,
                                 String side, String type, BigDecimal quantity,
                                 BigDecimal price, boolean reduceOnly, MarketType marketType) {
        String base = baseUrl(marketType);
        String binanceSymbol = toBinanceSymbol(symbol);
        String cacheKey = marketType.name() + ":" + binanceSymbol;

        BigDecimal alignedQty = alignToStepSize(cacheKey, binanceSymbol, base, marketType, quantity);
        if (alignedQty.compareTo(BigDecimal.ZERO) <= 0) {
            log.error("[Futures] quantity {} < stepSize for {}, cannot place order", quantity, binanceSymbol);
            throw new BizException(GlobalError.TRADE_API_ERROR);
        }
        if (alignedQty.compareTo(quantity) != 0) {
            log.info("[Futures] Quantity aligned by stepSize: {} → {} ({})", quantity, alignedQty, binanceSymbol);
        }

        // LIMIT 订单：按 PRICE_FILTER tickSize 向下截断价格（避免 Binance 精度拒单）
        BigDecimal alignedPrice = price;
        if ("LIMIT".equals(type) && price != null && price.compareTo(BigDecimal.ZERO) > 0) {
            alignedPrice = alignToTickSize(cacheKey, binanceSymbol, base, marketType, price);
            if (alignedPrice.compareTo(price) != 0) {
                log.info("[Futures] Price aligned by tickSize: {} → {} ({})", price, alignedPrice, binanceSymbol);
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
        if (isHedgeMode(apiKey, apiSecret, marketType)) {
            // 对冲模式（Hedge/Dual Position Mode）：所有订单必须携带 positionSide，不支持 reduceOnly
            // 开仓：BUY → positionSide=LONG，SELL → positionSide=SHORT
            // 平仓：BUY（平空）→ positionSide=SHORT，SELL（平多）→ positionSide=LONG
            String positionSide;
            if (reduceOnly) {
                positionSide = "SELL".equals(side) ? "LONG" : "SHORT";
            } else {
                positionSide = "BUY".equals(side) ? "LONG" : "SHORT";
            }
            params.append("&positionSide=").append(positionSide);
            log.info("[Futures] Hedge mode: positionSide={}, reduceOnly={}", positionSide, reduceOnly);
        } else if (reduceOnly) {
            params.append("&reduceOnly=true");
        }
        params.append("&recvWindow=").append(properties.getBinance().getRecvWindow());
        params.append("&timestamp=").append(System.currentTimeMillis());

        String signature = sign(params.toString(), apiSecret);
        params.append("&signature=").append(signature);

        String endpoint = marketType == MarketType.USDM ? "/fapi/v1/order" : "/dapi/v1/order";
        RequestBody body = RequestBody.create(params.toString(),
                MediaType.parse("application/x-www-form-urlencoded"));
        Request request = new Request.Builder()
                .url(base + endpoint)
                .post(body)
                .addHeader("X-MBX-APIKEY", apiKey)
                .build();

        return executeRequest(request, "placeOrder");
    }

    /**
     * 设置杠杆（开仓前调用，幂等安全）
     *
     * @param leverage   杠杆倍数（1-125）
     */
    public void setLeverage(String apiKey, String apiSecret, String symbol,
                             int leverage, MarketType marketType) {
        String base = baseUrl(marketType);
        String binanceSymbol = toBinanceSymbol(symbol);
        String endpoint = marketType == MarketType.USDM ? "/fapi/v1/leverage" : "/dapi/v1/leverage";

        StringBuilder params = new StringBuilder();
        params.append("symbol=").append(binanceSymbol);
        params.append("&leverage=").append(leverage);
        params.append("&recvWindow=").append(properties.getBinance().getRecvWindow());
        params.append("&timestamp=").append(System.currentTimeMillis());

        String signature = sign(params.toString(), apiSecret);
        params.append("&signature=").append(signature);

        RequestBody body = RequestBody.create(params.toString(),
                MediaType.parse("application/x-www-form-urlencoded"));
        Request request = new Request.Builder()
                .url(base + endpoint)
                .post(body)
                .addHeader("X-MBX-APIKEY", apiKey)
                .build();

        executeRequest(request, "setLeverage");
        log.info("[Futures] Leverage set: symbol={}, leverage={}", binanceSymbol, leverage);
    }

    /**
     * 设置保证金模式（ISOLATED/CROSS，幂等：若与当前相同则忽略 "No need" 错误）
     */
    public void setMarginType(String apiKey, String apiSecret, String symbol,
                               MarginType marginType, MarketType marketType) {
        String base = baseUrl(marketType);
        String binanceSymbol = toBinanceSymbol(symbol);
        String endpoint = marketType == MarketType.USDM ? "/fapi/v1/marginType" : "/dapi/v1/marginType";

        // Binance API 保证金模式：ISOLATED 原样，CROSS → CROSSED（与 Binance 文档一致）
        String binanceMarginType = marginType == MarginType.CROSS ? "CROSSED" : marginType.name();

        StringBuilder params = new StringBuilder();
        params.append("symbol=").append(binanceSymbol);
        params.append("&marginType=").append(binanceMarginType);
        params.append("&recvWindow=").append(properties.getBinance().getRecvWindow());
        params.append("&timestamp=").append(System.currentTimeMillis());

        String signature = sign(params.toString(), apiSecret);
        params.append("&signature=").append(signature);

        RequestBody body = RequestBody.create(params.toString(),
                MediaType.parse("application/x-www-form-urlencoded"));
        Request request = new Request.Builder()
                .url(base + endpoint)
                .post(body)
                .addHeader("X-MBX-APIKEY", apiKey)
                .build();

        // Binance 若保证金模式与当前相同，返回 HTTP 400 + body: {"code":-4046,"msg":"..."}，视为成功
        // 其他 4xx/5xx 错误必须向上抛出，不能静默吞掉
        try (Response response = httpClient.newCall(request).execute()) {
            String bodyStr = response.body() != null ? response.body().string() : "";
            if (response.isSuccessful()) {
                log.info("[Futures] MarginType set: symbol={}, marginType={}", binanceSymbol, binanceMarginType);
            } else {
                // 解析 Binance 业务错误码
                int binanceCode = 0;
                try {
                    binanceCode = JSON.parseObject(bodyStr).getIntValue("code");
                } catch (Exception ignored) { /* body 非 JSON 时忽略 */ }

                if (binanceCode == -4046) {
                    // -4046: "No need to change margin type." 保证金模式已是目标值，安全忽略
                    log.debug("[Futures] MarginType already set to {} for {}, skipping", binanceMarginType, binanceSymbol);
                } else {
                    log.error("[Futures] setMarginType failed: symbol={}, marginType={}, code={}, body={}",
                            binanceSymbol, binanceMarginType, response.code(), bodyStr);
                    throw new BizException(GlobalError.TRADE_API_ERROR);
                }
            }
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("[Futures] setMarginType error: {}", e.getMessage(), e);
            throw new BizException(GlobalError.TRADE_API_ERROR);
        }
    }

    /**
     * 查询持仓风险（强平价、未实现盈亏等）
     *
     * @param symbol 可为 null（查所有），或指定交易对
     */
    public JSONArray getPositionRisk(String apiKey, String apiSecret,
                                      String symbol, MarketType marketType) {
        String base = baseUrl(marketType);
        String endpoint = marketType == MarketType.USDM ? "/fapi/v2/positionRisk" : "/dapi/v1/positionRisk";

        StringBuilder params = new StringBuilder();
        if (symbol != null) {
            params.append("symbol=").append(toBinanceSymbol(symbol)).append("&");
        }
        params.append("recvWindow=").append(properties.getBinance().getRecvWindow());
        params.append("&timestamp=").append(System.currentTimeMillis());

        String signature = sign(params.toString(), apiSecret);
        params.append("&signature=").append(signature);

        Request request = new Request.Builder()
                .url(base + endpoint + "?" + params)
                .get()
                .addHeader("X-MBX-APIKEY", apiKey)
                .build();

        return executeArrayRequest(request, "getPositionRisk");
    }

    /**
     * 查询标记价格和资金费率（无需签名）
     *
     * @param symbol     交易对（如 "BTCUSDT"）
     * @param marketType USDM / COINM
     */
    public JSONObject getMarkPrice(String symbol, MarketType marketType) {
        String base = baseUrl(marketType);
        String endpoint = marketType == MarketType.USDM ? "/fapi/v1/premiumIndex" : "/dapi/v1/premiumIndex";
        String url = base + endpoint + "?symbol=" + toBinanceSymbol(symbol);

        Request request = new Request.Builder().url(url).get().build();
        return executeRequest(request, "getMarkPrice");
    }

    /**
     * 查询合约账户余额（USDM 返回 assets 数组，COINM 同理）
     */
    public JSONObject getAccount(String apiKey, String apiSecret, MarketType marketType) {
        String base = baseUrl(marketType);
        String endpoint = marketType == MarketType.USDM ? "/fapi/v2/account" : "/dapi/v1/account";

        StringBuilder params = new StringBuilder();
        params.append("recvWindow=").append(properties.getBinance().getRecvWindow());
        params.append("&timestamp=").append(System.currentTimeMillis());

        String signature = sign(params.toString(), apiSecret);
        params.append("&signature=").append(signature);

        Request request = new Request.Builder()
                .url(base + endpoint + "?" + params)
                .get()
                .addHeader("X-MBX-APIKEY", apiKey)
                .build();

        return executeRequest(request, "getAccount");
    }

    /**
     * 测试合约账户连通性
     */
    public boolean testConnection(String apiKey, String apiSecret, MarketType marketType) {
        try {
            JSONObject account = getAccount(apiKey, apiSecret, marketType);
            return account != null && (account.containsKey("assets") || account.containsKey("totalMarginBalance"));
        } catch (Exception e) {
            log.warn("[Futures] Connection test failed for {}: {}", marketType, e.getMessage());
            return false;
        }
    }

    // ─── LOT_SIZE 对齐 ──────────────────────────────────────

    private BigDecimal alignToStepSize(String cacheKey, String binanceSymbol,
                                        String base, MarketType marketType, BigDecimal quantity) {
        BigDecimal stepSize = getOrFetchStepSize(cacheKey, binanceSymbol, base, marketType);
        if (stepSize == null || stepSize.compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("[Futures LOT_SIZE] Cannot get stepSize for {}, using 8 decimal fallback", binanceSymbol);
            return quantity.setScale(8, RoundingMode.DOWN);
        }
        BigDecimal steps = quantity.divide(stepSize, 0, RoundingMode.DOWN);
        return steps.multiply(stepSize).stripTrailingZeros();
    }

    private BigDecimal getOrFetchStepSize(String cacheKey, String binanceSymbol,
                                           String base, MarketType marketType) {
        BigDecimal cached = stepSizeCache.get(cacheKey);
        if (cached != null) return cached;
        // 优先从 ExchangeSymbolCache（DB 同步数据）获取，避免下单路径上的阻塞 HTTP 调用
        TrdExchangeSymbol esym = exchangeSymbolCache.getExchangeSymbol("binance", marketType.name(), binanceSymbol);
        if (esym != null && esym.getLotSize() != null && esym.getLotSize().compareTo(BigDecimal.ZERO) > 0) {
            stepSizeCache.put(cacheKey, esym.getLotSize());
            // tickSize 一并填充，减少后续 alignToTickSize 再走一次 API
            if (esym.getTickSize() != null && esym.getTickSize().compareTo(BigDecimal.ZERO) > 0) {
                tickSizeCache.put(cacheKey, esym.getTickSize());
            }
            log.debug("[Futures] stepSize/tickSize loaded from ExchangeSymbolCache for {}", cacheKey);
            return esym.getLotSize();
        }
        // 兜底：ExchangeSymbolCache 未命中（首次部署 / 表为空）时，回退到 exchangeInfo API 调用
        fetchAndCacheFilters(binanceSymbol, base, marketType);
        return stepSizeCache.get(cacheKey);
    }

    /**
     * 一次 exchangeInfo 请求同时缓存 LOT_SIZE(stepSize) 和 PRICE_FILTER(tickSize)。
     * 两个缓存均以 "MARKETTYPE:SYMBOL" 为 key。
     */
    private void fetchAndCacheFilters(String binanceSymbol, String base, MarketType marketType) {
        String cacheKey = marketType.name() + ":" + binanceSymbol;
        try {
            String infoEndpoint = marketType == MarketType.USDM
                    ? "/fapi/v1/exchangeInfo" : "/dapi/v1/exchangeInfo";
            String url = base + infoEndpoint + "?symbol=" + binanceSymbol;
            Request request = new Request.Builder().url(url).get().build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    log.warn("[Futures exchangeInfo] failed for {}: {}", binanceSymbol, response.code());
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
                            stepSizeCache.put(cacheKey, step);
                            log.info("[Futures LOT_SIZE] {} stepSize={}", binanceSymbol, step.toPlainString());
                        }
                    } else if ("PRICE_FILTER".equals(filterType)) {
                        String tickStr = filter.getString("tickSize");
                        if (tickStr != null) {
                            BigDecimal tick = new BigDecimal(tickStr);
                            tickSizeCache.put(cacheKey, tick);
                            log.info("[Futures PRICE_FILTER] {} tickSize={}", binanceSymbol, tick.toPlainString());
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[Futures exchangeInfo] Failed to fetch filters for {}: {}", binanceSymbol, e.getMessage());
        }
    }

    // ─── PRICE_FILTER 对齐 ────────────────────────────────────

    /**
     * 按交易对 PRICE_FILTER tickSize 向下截断 LIMIT 价格。
     * <p>
     * 算法：floor(price / tickSize) * tickSize
     * 例：price=42000.157, tickSize=0.1 → floor(420001.57) * 0.1 = 42000.1
     * <p>
     * tickSize 从 Binance {@code /fapi/v1/exchangeInfo} 查询并缓存，生命周期同 stepSize 缓存。
     * 若查询失败则返回原价格（不阻断下单，Binance 如拒绝将在 executeRequest 中抛出）。
     */
    private BigDecimal alignToTickSize(String cacheKey, String binanceSymbol,
                                        String base, MarketType marketType, BigDecimal price) {
        BigDecimal tickSize = tickSizeCache.get(cacheKey);
        if (tickSize == null) {
            // 独立查询 ExchangeSymbolCache，不依赖 getOrFetchStepSize 的副作用
            // （getOrFetchStepSize 会顺带填充 tickSizeCache，但若 alignToTickSize 单独调用则无该副作用）
            TrdExchangeSymbol esym = exchangeSymbolCache.getExchangeSymbol("binance", marketType.name(), binanceSymbol);
            if (esym != null && esym.getTickSize() != null && esym.getTickSize().compareTo(BigDecimal.ZERO) > 0) {
                tickSize = esym.getTickSize();
                tickSizeCache.put(cacheKey, tickSize);
            }
        }
        if (tickSize == null) {
            // 仍未命中（ExchangeSymbolCache 也无数据）：回退到 exchangeInfo API 调用
            fetchAndCacheFilters(binanceSymbol, base, marketType);
            tickSize = tickSizeCache.get(cacheKey);
        }
        if (tickSize == null || tickSize.compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("[Futures PRICE_FILTER] Cannot get tickSize for {}, using price as-is", binanceSymbol);
            return price;
        }
        BigDecimal ticks = price.divide(tickSize, 0, RoundingMode.DOWN);
        return ticks.multiply(tickSize).stripTrailingZeros();
    }

    // ─── 账户模式 ──────────────────────────────────────────

    /**
     * 查询账户是否为对冲模式（Hedge/Dual Position Mode）。
     * 结果按 apiKey+marketType 缓存，避免重复调用。
     * <p>
     * 对冲模式下不支持 reduceOnly，平仓需使用 positionSide=LONG/SHORT。
     * 查询失败时默认返回 false（单向模式），保持现有行为。
     */
    private boolean isHedgeMode(String apiKey, String apiSecret, MarketType marketType) {
        String cacheKey = apiKey + ":" + marketType.name();
        return hedgeModeCache.computeIfAbsent(cacheKey, k -> {
            try {
                String base = baseUrl(marketType);
                String endpoint = marketType == MarketType.USDM
                        ? "/fapi/v1/positionSide/dual"
                        : "/dapi/v1/positionSide/dual";
                long ts = System.currentTimeMillis();
                String query = "timestamp=" + ts + "&recvWindow=" + properties.getBinance().getRecvWindow();
                String sig = sign(query, apiSecret);
                Request req = new Request.Builder()
                        .url(base + endpoint + "?" + query + "&signature=" + sig)
                        .get()
                        .addHeader("X-MBX-APIKEY", apiKey)
                        .build();
                JSONObject resp = executeRequest(req, "getPositionMode");
                boolean hedge = Boolean.TRUE.equals(resp.getBoolean("dualSidePosition"));
                log.info("[Futures] Account position mode: {}", hedge ? "HEDGE" : "ONE-WAY");
                return hedge;
            } catch (Exception e) {
                log.warn("[Futures] Failed to query position mode, assuming ONE-WAY: {}", e.getMessage());
                return false;
            }
        });
    }

    // ─── HTTP 辅助 ─────────────────────────────────────────

    private JSONObject executeRequest(Request request, String op) {
        try (Response response = httpClient.newCall(request).execute()) {
            ResponseBody rb = response.body();
            String bodyStr = rb != null ? rb.string() : "";

            if (!response.isSuccessful()) {
                String binanceMsg = extractBinanceMsg(bodyStr);
                log.error("[Futures] {} failed, code={}, body={}", op, response.code(), bodyStr);
                throw new BizException(GlobalError.TRADE_API_ERROR, binanceMsg);
            }
            return JSON.parseObject(bodyStr);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("[Futures] {} error: {}", op, e.getMessage(), e);
            throw new BizException(GlobalError.TRADE_API_ERROR);
        }
    }

    private JSONArray executeArrayRequest(Request request, String op) {
        try (Response response = httpClient.newCall(request).execute()) {
            ResponseBody rb = response.body();
            String bodyStr = rb != null ? rb.string() : "";

            if (!response.isSuccessful()) {
                String binanceMsg = extractBinanceMsg(bodyStr);
                log.error("[Futures] {} failed, code={}, body={}", op, response.code(), bodyStr);
                throw new BizException(GlobalError.TRADE_API_ERROR, binanceMsg);
            }
            return JSON.parseArray(bodyStr);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("[Futures] {} error: {}", op, e.getMessage(), e);
            throw new BizException(GlobalError.TRADE_API_ERROR);
        }
    }

    /** 从 Binance 错误响应体中提取 msg 字段，格式：{"code":-1111,"msg":"..."} */
    private String extractBinanceMsg(String body) {
        try {
            JSONObject obj = JSON.parseObject(body);
            String msg = obj.getString("msg");
            int code = obj.getIntValue("code");
            return code != 0 ? "[" + code + "] " + msg : msg;
        } catch (Exception ignored) {
            return body;
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
            throw new BizException(GlobalError.TRADE_API_ERROR);
        }
    }

    private String baseUrl(MarketType marketType) {
        return marketType == MarketType.COINM ? COINM_BASE : USDM_BASE;
    }

    /** BTC-USDT → BTCUSDT */
    private String toBinanceSymbol(String symbol) {
        return symbol.replace("-", "").toUpperCase();
    }
}

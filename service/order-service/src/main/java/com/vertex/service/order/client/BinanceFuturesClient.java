package com.vertex.service.order.client;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.vertex.common.core.GlobalError;
import com.vertex.common.core.exception.BizException;
import com.vertex.model.entity.trading.MarginType;
import com.vertex.model.entity.trading.MarketType;
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

    /** USDM 合约 Base URL */
    private static final String USDM_BASE = "https://fapi.binance.com";
    /** COINM 合约 Base URL */
    private static final String COINM_BASE = "https://dapi.binance.com";

    /** 交易对 stepSize 缓存（"USDM:BTCUSDT" → stepSize） */
    private final ConcurrentHashMap<String, BigDecimal> stepSizeCache = new ConcurrentHashMap<>();

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

        StringBuilder params = new StringBuilder();
        params.append("symbol=").append(binanceSymbol);
        params.append("&side=").append(side);
        params.append("&type=").append(type);
        params.append("&quantity=").append(alignedQty.stripTrailingZeros().toPlainString());
        if ("LIMIT".equals(type) && price != null) {
            params.append("&timeInForce=GTC");
            params.append("&price=").append(price.stripTrailingZeros().toPlainString());
        }
        if (reduceOnly) {
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

        StringBuilder params = new StringBuilder();
        params.append("symbol=").append(binanceSymbol);
        params.append("&marginType=").append(marginType.name());
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

        // Binance 若保证金模式与当前相同，返回 code=-4046 "No need to change margin type"，视为成功
        try {
            executeRequest(request, "setMarginType");
            log.info("[Futures] MarginType set: symbol={}, marginType={}", binanceSymbol, marginType);
        } catch (BizException e) {
            // 忽略 "no need to change" 错误，其他异常继续抛出
            log.info("[Futures] MarginType already set or no change needed for {}, skipping", binanceSymbol);
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
        BigDecimal stepSize = stepSizeCache.computeIfAbsent(cacheKey,
                k -> fetchStepSize(binanceSymbol, base, marketType));
        if (stepSize == null || stepSize.compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("[Futures LOT_SIZE] Cannot get stepSize for {}, using 8 decimal fallback", binanceSymbol);
            return quantity.setScale(8, RoundingMode.DOWN);
        }
        BigDecimal steps = quantity.divide(stepSize, 0, RoundingMode.DOWN);
        return steps.multiply(stepSize).stripTrailingZeros();
    }

    private BigDecimal fetchStepSize(String binanceSymbol, String base, MarketType marketType) {
        try {
            String infoEndpoint = marketType == MarketType.USDM
                    ? "/fapi/v1/exchangeInfo" : "/dapi/v1/exchangeInfo";
            String url = base + infoEndpoint + "?symbol=" + binanceSymbol;
            Request request = new Request.Builder().url(url).get().build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    log.warn("[Futures LOT_SIZE] exchangeInfo failed for {}: {}", binanceSymbol, response.code());
                    return null;
                }
                ResponseBody rb = response.body();
                if (rb == null) return null;

                JSONObject body = JSON.parseObject(rb.string());
                JSONArray symbols = body.getJSONArray("symbols");
                if (symbols == null || symbols.isEmpty()) return null;

                JSONObject symbolInfo = symbols.getJSONObject(0);
                JSONArray filters = symbolInfo.getJSONArray("filters");
                if (filters == null) return null;

                for (int i = 0; i < filters.size(); i++) {
                    JSONObject filter = filters.getJSONObject(i);
                    if ("LOT_SIZE".equals(filter.getString("filterType"))) {
                        String stepStr = filter.getString("stepSize");
                        if (stepStr != null) {
                            BigDecimal step = new BigDecimal(stepStr);
                            log.info("[Futures LOT_SIZE] {} stepSize={}", binanceSymbol, step.toPlainString());
                            return step;
                        }
                    }
                }
                log.warn("[Futures LOT_SIZE] No LOT_SIZE filter for {}", binanceSymbol);
                return null;
            }
        } catch (Exception e) {
            log.warn("[Futures LOT_SIZE] Failed to fetch stepSize for {}: {}", binanceSymbol, e.getMessage());
            return null;
        }
    }

    // ─── HTTP 辅助 ─────────────────────────────────────────

    private JSONObject executeRequest(Request request, String op) {
        try (Response response = httpClient.newCall(request).execute()) {
            ResponseBody rb = response.body();
            String bodyStr = rb != null ? rb.string() : "";

            if (!response.isSuccessful()) {
                log.error("[Futures] {} failed, code={}, body={}", op, response.code(), bodyStr);
                throw new BizException(GlobalError.TRADE_API_ERROR);
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
                log.error("[Futures] {} failed, code={}, body={}", op, response.code(), bodyStr);
                throw new BizException(GlobalError.TRADE_API_ERROR);
            }
            return JSON.parseArray(bodyStr);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("[Futures] {} error: {}", op, e.getMessage(), e);
            throw new BizException(GlobalError.TRADE_API_ERROR);
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

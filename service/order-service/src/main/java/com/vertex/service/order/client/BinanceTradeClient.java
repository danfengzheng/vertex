package com.vertex.service.order.client;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.vertex.common.core.GlobalError;
import com.vertex.common.core.exception.BizException;
import com.vertex.service.order.config.TradingProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 币安现货交易 REST API 客户端
 * <p>
 * 支持下单、撤单、查询订单、查询账户余额。
 * 所有请求使用 HMAC-SHA256 签名。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BinanceTradeClient {

    private final OkHttpClient httpClient;
    private final TradingProperties properties;

    /**
     * 下单
     *
     * @param apiKey    解密后的 API Key
     * @param apiSecret 解密后的 API Secret
     * @param symbol    交易对（BTC-USDT 格式）
     * @param side      BUY / SELL
     * @param type      MARKET / LIMIT
     * @param quantity  数量
     * @param price     价格（LIMIT 订单必填，MARKET 可为 null）
     * @return 交易所返回的订单信息
     */
    public JSONObject placeOrder(String apiKey, String apiSecret, String symbol,
                                 String side, String type, BigDecimal quantity, BigDecimal price) {
        String binanceSymbol = toBinanceSymbol(symbol);
        StringBuilder params = new StringBuilder();
        params.append("symbol=").append(binanceSymbol);
        params.append("&side=").append(side);
        params.append("&type=").append(type);
        params.append("&quantity=").append(quantity.stripTrailingZeros().toPlainString());
        if ("LIMIT".equals(type) && price != null) {
            params.append("&timeInForce=GTC");
            params.append("&price=").append(price.stripTrailingZeros().toPlainString());
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

    // ─── 内部方法 ───────────────────────────────────

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

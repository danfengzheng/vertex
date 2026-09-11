package com.vertex.service.mm.binance;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.vertex.service.mm.config.MmProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * 币安账户预设 client —— LIVE 策略启动前必须调用一次。
 * <p>
 * 三个方法都是 <b>幂等</b> 的：
 * <ul>
 *   <li>{@code setDualSideMode(true)}：账户级；已开启时返回 -4059 or -4046，忽略</li>
 *   <li>{@code setLeverage(symbol, N)}：symbol 级；已相同时返回成功；不同则改</li>
 *   <li>{@code setMarginType(symbol, ISOLATED)}：symbol 级；已相同时返回 -4046，忽略</li>
 * </ul>
 * </p>
 * <p>
 * 还提供 {@code fetchIncome} 拉资金费率用于 PnlSnapshot。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BinanceAccountClient {

    private static final int ERR_NO_NEED_TO_CHANGE = -4046;   // 已经是目标值
    private static final int ERR_DUAL_ALREADY_ON = -4059;      // dual mode 已开
    private static final int ERR_POSITION_EXISTS = -4048;      // 有仓位不能改保证金模式（需先平仓）
    private static final MediaType FORM = MediaType.parse("application/x-www-form-urlencoded");

    private final MmProperties properties;
    private final BinanceTimeSync timeSync;

    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build();

    /**
     * 开启 dual position (hedge) 模式。已开启时静默通过。
     * @return true = 现在是 dual mode；false = 出错
     */
    public boolean ensureDualSideMode(String apiKey, String apiSecret) {
        String params = "dualSidePosition=true&recvWindow=10000&timestamp=" + timeSync.now();
        String signature = sign(params, apiSecret);
        Request req = new Request.Builder()
                .url(properties.getBinanceUsdcmBaseUrl() + "/fapi/v1/positionSide/dual")
                .post(RequestBody.create(params + "&signature=" + signature, FORM))
                .addHeader("X-MBX-APIKEY", apiKey)
                .build();
        try (Response resp = http.newCall(req).execute()) {
            String body = resp.body() == null ? "" : resp.body().string();
            if (resp.isSuccessful()) {
                log.info("[BinanceAccount] dualSideMode set to TRUE");
                return true;
            }
            Integer code = extractCode(body);
            if (code != null && (code == ERR_NO_NEED_TO_CHANGE || code == ERR_DUAL_ALREADY_ON)) {
                log.info("[BinanceAccount] dualSideMode already ON (code={})", code);
                return true;
            }
            log.error("[BinanceAccount] setDualSideMode failed HTTP {}: {}", resp.code(), body);
            return false;
        } catch (Exception ex) {
            log.error("[BinanceAccount] setDualSideMode error: {}", ex.getMessage());
            return false;
        }
    }

    /** 设置某 symbol 的杠杆。已相同时也返回 true（币安接受重复请求） */
    public boolean setLeverage(String apiKey, String apiSecret, String symbol, int leverage) {
        String params = "symbol=" + symbol + "&leverage=" + leverage
                + "&recvWindow=10000&timestamp=" + timeSync.now();
        String signature = sign(params, apiSecret);
        Request req = new Request.Builder()
                .url(properties.getBinanceUsdcmBaseUrl() + "/fapi/v1/leverage")
                .post(RequestBody.create(params + "&signature=" + signature, FORM))
                .addHeader("X-MBX-APIKEY", apiKey)
                .build();
        try (Response resp = http.newCall(req).execute()) {
            String body = resp.body() == null ? "" : resp.body().string();
            if (resp.isSuccessful()) {
                log.info("[BinanceAccount] {} leverage set to {}x", symbol, leverage);
                return true;
            }
            log.error("[BinanceAccount] setLeverage {} {} failed: {}", symbol, leverage, body);
            return false;
        } catch (Exception ex) {
            log.error("[BinanceAccount] setLeverage error: {}", ex.getMessage());
            return false;
        }
    }

    /** 设置保证金模式。marginType = ISOLATED / CROSSED */
    public boolean setMarginType(String apiKey, String apiSecret, String symbol, String marginType) {
        String mt = "CROSS".equalsIgnoreCase(marginType) ? "CROSSED" : marginType.toUpperCase();
        String params = "symbol=" + symbol + "&marginType=" + mt
                + "&recvWindow=10000&timestamp=" + timeSync.now();
        String signature = sign(params, apiSecret);
        Request req = new Request.Builder()
                .url(properties.getBinanceUsdcmBaseUrl() + "/fapi/v1/marginType")
                .post(RequestBody.create(params + "&signature=" + signature, FORM))
                .addHeader("X-MBX-APIKEY", apiKey)
                .build();
        try (Response resp = http.newCall(req).execute()) {
            String body = resp.body() == null ? "" : resp.body().string();
            if (resp.isSuccessful()) {
                log.info("[BinanceAccount] {} marginType set to {}", symbol, mt);
                return true;
            }
            Integer code = extractCode(body);
            if (code != null && code == ERR_NO_NEED_TO_CHANGE) {
                log.info("[BinanceAccount] {} marginType already {}", symbol, mt);
                return true;
            }
            if (code != null && code == ERR_POSITION_EXISTS) {
                log.warn("[BinanceAccount] {} marginType change rejected: position exists; keep current setting", symbol);
                return true;   // 有仓位不改，忽略
            }
            log.error("[BinanceAccount] setMarginType {} {} failed: {}", symbol, mt, body);
            return false;
        } catch (Exception ex) {
            log.error("[BinanceAccount] setMarginType error: {}", ex.getMessage());
            return false;
        }
    }

    /**
     * 拉最近 N 条 FUNDING_FEE 收入 → 汇总为该 symbol 的累积 fundingPnl。
     * @param symbol 交易对
     * @param startTimeMs 开始时间
     * @return sum(income) —— 正数=你收资金费，负数=你付
     */
    public BigDecimal fetchFundingIncome(String apiKey, String apiSecret, String symbol, long startTimeMs) {
        String params = "symbol=" + symbol + "&incomeType=FUNDING_FEE"
                + "&startTime=" + startTimeMs
                + "&limit=1000&recvWindow=10000&timestamp=" + timeSync.now();
        String signature = sign(params, apiSecret);
        Request req = new Request.Builder()
                .url(properties.getBinanceUsdcmBaseUrl() + "/fapi/v1/income?" + params + "&signature=" + signature)
                .get()
                .addHeader("X-MBX-APIKEY", apiKey)
                .build();
        try (Response resp = http.newCall(req).execute()) {
            String body = resp.body() == null ? "" : resp.body().string();
            if (!resp.isSuccessful()) {
                log.warn("[BinanceAccount] fetchFundingIncome {} failed: {}", symbol, body);
                return BigDecimal.ZERO;
            }
            JSONArray arr = JSON.parseArray(body);
            BigDecimal sum = BigDecimal.ZERO;
            for (int i = 0; i < arr.size(); i++) {
                JSONObject o = arr.getJSONObject(i);
                String income = o.getString("income");
                if (income != null) sum = sum.add(new BigDecimal(income));
            }
            return sum;
        } catch (Exception ex) {
            log.warn("[BinanceAccount] fetchFundingIncome error: {}", ex.getMessage());
            return BigDecimal.ZERO;
        }
    }

    /**
     * 拉某 symbol 的双向持仓（hedge mode）。返回 [LONG_qty, SHORT_qty]，绝对值。
     * 币安 /fapi/v3/positionRisk 返回 positionAmt，LONG 正数、SHORT 负数。
     * @return long[]{longQty, shortQty}，出错返回 null
     */
    public java.math.BigDecimal[] fetchPositionRisk(String apiKey, String apiSecret, String symbol) {
        String params = "symbol=" + symbol + "&recvWindow=10000&timestamp=" + timeSync.now();
        String signature = sign(params, apiSecret);
        Request req = new Request.Builder()
                .url(properties.getBinanceUsdcmBaseUrl() + "/fapi/v3/positionRisk?" + params + "&signature=" + signature)
                .get()
                .addHeader("X-MBX-APIKEY", apiKey)
                .build();
        try (Response resp = http.newCall(req).execute()) {
            String body = resp.body() == null ? "" : resp.body().string();
            if (!resp.isSuccessful()) {
                log.warn("[BinanceAccount] fetchPositionRisk {} failed: {}", symbol, body);
                return null;
            }
            JSONArray arr = JSON.parseArray(body);
            java.math.BigDecimal longQty = java.math.BigDecimal.ZERO;
            java.math.BigDecimal shortQty = java.math.BigDecimal.ZERO;
            for (int i = 0; i < arr.size(); i++) {
                JSONObject o = arr.getJSONObject(i);
                String ps = o.getString("positionSide");
                java.math.BigDecimal amt = new java.math.BigDecimal(o.getString("positionAmt")).abs();
                if ("LONG".equalsIgnoreCase(ps)) longQty = amt;
                else if ("SHORT".equalsIgnoreCase(ps)) shortQty = amt;
                else { // one-way mode — 兼容
                    if (amt.compareTo(java.math.BigDecimal.ZERO) > 0) longQty = amt;
                }
            }
            return new java.math.BigDecimal[]{longQty, shortQty};
        } catch (Exception ex) {
            log.warn("[BinanceAccount] fetchPositionRisk error: {}", ex.getMessage());
            return null;
        }
    }

    /**
     * 拉某 symbol 的所有 openOrders。用于反向对账检测孤儿单。
     * @return List of {orderId, clientOrderId, side, positionSide, price, origQty, executedQty}
     */
    public java.util.List<JSONObject> fetchOpenOrders(String apiKey, String apiSecret, String symbol) {
        String params = "symbol=" + symbol + "&recvWindow=10000&timestamp=" + timeSync.now();
        String signature = sign(params, apiSecret);
        Request req = new Request.Builder()
                .url(properties.getBinanceUsdcmBaseUrl() + "/fapi/v1/openOrders?" + params + "&signature=" + signature)
                .get()
                .addHeader("X-MBX-APIKEY", apiKey)
                .build();
        try (Response resp = http.newCall(req).execute()) {
            String body = resp.body() == null ? "" : resp.body().string();
            if (!resp.isSuccessful()) {
                log.warn("[BinanceAccount] fetchOpenOrders {} failed: {}", symbol, body);
                return java.util.List.of();
            }
            JSONArray arr = JSON.parseArray(body);
            java.util.List<JSONObject> out = new java.util.ArrayList<>(arr.size());
            for (int i = 0; i < arr.size(); i++) out.add(arr.getJSONObject(i));
            return out;
        } catch (Exception ex) {
            log.warn("[BinanceAccount] fetchOpenOrders error: {}", ex.getMessage());
            return java.util.List.of();
        }
    }

    private Integer extractCode(String body) {
        try {
            JSONObject o = JSON.parseObject(body);
            return o.getInteger("code");
        } catch (Exception ignored) {
            return null;
        }
    }

    private String sign(String data, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            throw new RuntimeException("sign error", e);
        }
    }
}

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
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * mm-service 专用币安下单 client。
 * <p>
 * <b>严格 Maker-only</b>：所有 LIMIT 单硬编码 {@code timeInForce=GTX}
 * （Good Till Crossing = post-only）。GTX 保证订单在到达 book 时若会立即成交（作为 taker）
 * 则被交易所拒绝，绝不产生 taker 手续费。
 * </p>
 * <p>
 * 不复用 order-service 的 {@code BinanceFuturesClient} —— 那个 client 用 GTC + 支持 market 单，
 * 语义完全不同，此处独立干净的做市专用实现。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MmOrderClient {

    private static final int ERR_POST_ONLY_REJECT = -5022;   // 币安 GTX 拒绝
    private static final int ERR_ORDER_WOULD_TRIGGER = -2010; // 通用挂单被拒（包括价格穿透）

    private final MmProperties properties;
    private final BinanceTimeSync timeSync;

    private static final String RECV_WINDOW = "&recvWindow=10000";

    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build();

    // ─── 单笔挂单 ─────────────────────────────────────────────

    public MmOrderResult placeOrder(String apiKey, String apiSecret, MmOrderRequest req) {
        String params = buildOrderParams(req);
        return signAndPost("/fapi/v1/order", apiKey, apiSecret, params, "placeOrder");
    }

    // ─── 批量挂单（支持一次 5 单，币安上限）──────────────────────

    public List<MmOrderResult> placeBatchOrders(String apiKey, String apiSecret, List<MmOrderRequest> requests) {
        if (requests == null || requests.isEmpty()) return Collections.emptyList();
        if (requests.size() > 5) {
            throw new IllegalArgumentException("batchOrders 最多支持 5 笔（币安限制），实际 " + requests.size());
        }

        JSONArray batch = new JSONArray();
        for (MmOrderRequest r : requests) {
            JSONObject o = new JSONObject();
            o.put("symbol", r.getSymbol());
            o.put("side", r.getSide());
            o.put("type", "LIMIT");
            o.put("timeInForce", "GTX");
            o.put("quantity", stripped(r.getQuantity()));
            o.put("price", stripped(r.getPrice()));
            if (r.getPositionSide() != null) o.put("positionSide", r.getPositionSide());
            if (r.getClientOrderId() != null) o.put("newClientOrderId", r.getClientOrderId());
            batch.add(o);
        }

        String encoded = URLEncoder.encode(batch.toJSONString(), StandardCharsets.UTF_8);
        StringBuilder params = new StringBuilder();
        params.append("batchOrders=").append(encoded);
        params.append(RECV_WINDOW).append("&timestamp=").append(timeSync.now());

        String signature = sign(params.toString(), apiSecret);
        params.append("&signature=").append(signature);

        Request request = new Request.Builder()
                .url(properties.getBinanceUsdcmBaseUrl() + "/fapi/v1/batchOrders")
                .post(RequestBody.create(params.toString(),
                        MediaType.parse("application/x-www-form-urlencoded")))
                .addHeader("X-MBX-APIKEY", apiKey)
                .build();

        try (Response resp = http.newCall(request).execute()) {
            String body = resp.body() == null ? "" : resp.body().string();
            if (!resp.isSuccessful()) {
                log.warn("[MmOrder] batchOrders HTTP {}: {}", resp.code(), body);
                // 返回 N 个失败
                List<MmOrderResult> failed = new ArrayList<>(requests.size());
                for (int i = 0; i < requests.size(); i++) {
                    failed.add(MmOrderResult.builder()
                            .success(false)
                            .clientOrderId(requests.get(i).getClientOrderId())
                            .errorCode(resp.code())
                            .errorMessage(body)
                            .build());
                }
                return failed;
            }
            JSONArray arr = JSON.parseArray(body);
            List<MmOrderResult> out = new ArrayList<>(arr.size());
            for (int i = 0; i < arr.size(); i++) {
                JSONObject o = arr.getJSONObject(i);
                out.add(parseResponse(o, requests.get(i).getClientOrderId()));
            }
            return out;
        } catch (IOException e) {
            log.error("[MmOrder] batchOrders network error: {}", e.getMessage());
            throw new RuntimeException(e);
        }
    }

    // ─── MARKET 单（仅 escape hatch 用；破例 taker，收 fee！）──────────

    /**
     * 市价强平 —— <b>只在 escape hatch 场景使用</b>！
     * <p>
     * 与所有其他方法不同，此方法**不用 GTX**，会作为 taker 成交并**收取手续费**。
     * 只在极端行情下 Layer 3 追单失败且用户配了 {@code emergency_taker_fallback_enabled=1} 才调用。
     * 每次调用会记录 grid_event 严重级别。
     * </p>
     */
    public MmOrderResult placeMarketOrder(String apiKey, String apiSecret, String symbol,
                                            String side, String positionSide, BigDecimal quantity,
                                            String clientOrderId) {
        StringBuilder p = new StringBuilder();
        p.append("symbol=").append(symbol);
        p.append("&side=").append(side);
        p.append("&type=MARKET");
        p.append("&quantity=").append(stripped(quantity));
        if (positionSide != null) p.append("&positionSide=").append(positionSide);
        if (clientOrderId != null) p.append("&newClientOrderId=").append(clientOrderId);
        p.append(RECV_WINDOW).append("&timestamp=").append(timeSync.now());
        log.warn("[MmOrder] MARKET FALLBACK order (breaks maker-only invariant!): symbol={} side={} qty={}",
                symbol, side, quantity);
        return signAndPost("/fapi/v1/order", apiKey, apiSecret, p.toString(), "placeMarketOrder");
    }

    // ─── 撤单 ────────────────────────────────────────────────

    public boolean cancelOrder(String apiKey, String apiSecret, String symbol, Long exchangeOrderId) {
        StringBuilder params = new StringBuilder();
        params.append("symbol=").append(symbol);
        params.append("&orderId=").append(exchangeOrderId);
        params.append(RECV_WINDOW).append("&timestamp=").append(timeSync.now());

        String signature = sign(params.toString(), apiSecret);
        params.append("&signature=").append(signature);

        Request req = new Request.Builder()
                .url(properties.getBinanceUsdcmBaseUrl() + "/fapi/v1/order?" + params)
                .delete()
                .addHeader("X-MBX-APIKEY", apiKey)
                .build();

        try (Response resp = http.newCall(req).execute()) {
            String body = resp.body() == null ? "" : resp.body().string();
            if (!resp.isSuccessful()) {
                log.warn("[MmOrder] cancel HTTP {} for orderId={}: {}", resp.code(), exchangeOrderId, body);
                return false;
            }
            return true;
        } catch (IOException e) {
            log.warn("[MmOrder] cancel network error: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 批量撤单（币安 supports 10 orderIds per request）。
     * @return 一一对应的成功标志
     */
    public List<Boolean> cancelBatchOrders(String apiKey, String apiSecret, String symbol, List<Long> exchangeOrderIds) {
        if (exchangeOrderIds == null || exchangeOrderIds.isEmpty()) return Collections.emptyList();
        if (exchangeOrderIds.size() > 10) {
            throw new IllegalArgumentException("batchCancel 最多 10 笔");
        }

        JSONArray arr = new JSONArray();
        for (Long id : exchangeOrderIds) arr.add(id);
        String encoded = URLEncoder.encode(arr.toJSONString(), StandardCharsets.UTF_8);

        StringBuilder params = new StringBuilder();
        params.append("symbol=").append(symbol);
        params.append("&orderIdList=").append(encoded);
        params.append(RECV_WINDOW).append("&timestamp=").append(timeSync.now());

        String signature = sign(params.toString(), apiSecret);
        params.append("&signature=").append(signature);

        Request req = new Request.Builder()
                .url(properties.getBinanceUsdcmBaseUrl() + "/fapi/v1/batchOrders?" + params)
                .delete()
                .addHeader("X-MBX-APIKEY", apiKey)
                .build();

        try (Response resp = http.newCall(req).execute()) {
            String body = resp.body() == null ? "" : resp.body().string();
            if (!resp.isSuccessful()) {
                log.warn("[MmOrder] batchCancel HTTP {}: {}", resp.code(), body);
                List<Boolean> out = new ArrayList<>(exchangeOrderIds.size());
                for (int i = 0; i < exchangeOrderIds.size(); i++) out.add(false);
                return out;
            }
            JSONArray respArr = JSON.parseArray(body);
            List<Boolean> out = new ArrayList<>(respArr.size());
            for (int i = 0; i < respArr.size(); i++) {
                JSONObject o = respArr.getJSONObject(i);
                Integer code = o.getInteger("code");
                out.add(code == null || code == 200);
            }
            return out;
        } catch (IOException e) {
            log.warn("[MmOrder] batchCancel network error: {}", e.getMessage());
            List<Boolean> out = new ArrayList<>(exchangeOrderIds.size());
            for (int i = 0; i < exchangeOrderIds.size(); i++) out.add(false);
            return out;
        }
    }

    // ─── 内部：构造 + 签名 + 发送 ─────────────────────────────

    private String buildOrderParams(MmOrderRequest r) {
        StringBuilder p = new StringBuilder();
        p.append("symbol=").append(r.getSymbol());
        p.append("&side=").append(r.getSide());
        p.append("&type=LIMIT");
        p.append("&timeInForce=GTX");   // 核心：GTX 硬编码
        p.append("&quantity=").append(stripped(r.getQuantity()));
        p.append("&price=").append(stripped(r.getPrice()));
        if (r.getPositionSide() != null) {
            p.append("&positionSide=").append(r.getPositionSide());
        }
        if (r.getClientOrderId() != null) {
            p.append("&newClientOrderId=").append(r.getClientOrderId());
        }
        p.append(RECV_WINDOW).append("&timestamp=").append(timeSync.now());
        return p.toString();
    }

    private MmOrderResult signAndPost(String endpoint, String apiKey, String apiSecret,
                                       String params, String opName) {
        String signature = sign(params, apiSecret);
        String signed = params + "&signature=" + signature;

        Request request = new Request.Builder()
                .url(properties.getBinanceUsdcmBaseUrl() + endpoint)
                .post(RequestBody.create(signed, MediaType.parse("application/x-www-form-urlencoded")))
                .addHeader("X-MBX-APIKEY", apiKey)
                .build();

        try (Response resp = http.newCall(request).execute()) {
            String body = resp.body() == null ? "" : resp.body().string();
            if (!resp.isSuccessful()) {
                JSONObject err = safeParseObj(body);
                Integer code = err == null ? null : err.getInteger("code");
                String msg = err == null ? body : err.getString("msg");
                boolean gtx = code != null && (code == ERR_POST_ONLY_REJECT || code == ERR_ORDER_WOULD_TRIGGER);
                if (gtx) {
                    log.info("[MmOrder] {} GTX rejected: code={} msg={}", opName, code, msg);
                } else {
                    log.warn("[MmOrder] {} HTTP {} code={}, msg={}", opName, resp.code(), code, msg);
                }
                return MmOrderResult.builder()
                        .success(false)
                        .errorCode(code)
                        .errorMessage(msg)
                        .gtxRejected(gtx)
                        .build();
            }
            JSONObject json = JSON.parseObject(body);
            return parseResponse(json, null);
        } catch (IOException e) {
            log.error("[MmOrder] {} network error: {}", opName, e.getMessage());
            return MmOrderResult.builder()
                    .success(false)
                    .errorMessage(e.getMessage())
                    .build();
        }
    }

    private MmOrderResult parseResponse(JSONObject o, String fallbackClientId) {
        Integer errorCode = o.getInteger("code");
        if (errorCode != null && errorCode != 200 && errorCode < 0) {
            String msg = o.getString("msg");
            boolean gtx = errorCode == ERR_POST_ONLY_REJECT || errorCode == ERR_ORDER_WOULD_TRIGGER;
            return MmOrderResult.builder()
                    .success(false)
                    .clientOrderId(fallbackClientId)
                    .errorCode(errorCode)
                    .errorMessage(msg)
                    .gtxRejected(gtx)
                    .build();
        }
        return MmOrderResult.builder()
                .success(true)
                .exchangeOrderId(o.getLong("orderId"))
                .clientOrderId(o.getString("clientOrderId"))
                .status(o.getString("status"))
                .executedQty(o.getBigDecimal("executedQty"))
                .avgPrice(o.getBigDecimal("avgPrice"))
                .build();
    }

    private static JSONObject safeParseObj(String s) {
        try { return JSON.parseObject(s); } catch (Exception e) { return null; }
    }

    private static String stripped(BigDecimal v) {
        return v == null ? "" : v.stripTrailingZeros().toPlainString();
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
            throw new RuntimeException("signing failed", e);
        }
    }
}

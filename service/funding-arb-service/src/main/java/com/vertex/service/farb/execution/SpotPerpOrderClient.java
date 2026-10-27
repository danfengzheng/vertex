package com.vertex.service.farb.execution;

import com.alibaba.fastjson2.JSONObject;
import com.vertex.model.entity.trading.MarketType;
import com.vertex.service.farb.config.FarbProperties;
import com.vertex.service.order.client.BinanceFuturesClient;
import com.vertex.service.order.client.BinanceTradeClient;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;

/**
 * 现货 + 期货统一下单门面。
 * <p>
 * v2.1 §6.2 订单类型策略：入场/正常出场用 IOC LIMIT（等价 MARKET 但限滑点），紧急平仓走 MARKET。
 * PAPER 模式的实现待 Sprint 6 接入；Sprint 4 只做 LIVE 骨架。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SpotPerpOrderClient {

    /** IOC LIMIT 滑点上限：0.1%（BUY: ask × 1.001 / SELL: bid × 0.999）*/
    public static final BigDecimal IOC_SLIPPAGE_CAP = new BigDecimal("0.001");

    private final BinanceTradeClient spotClient;
    private final BinanceFuturesClient futuresClient;
    private final FarbProperties properties;

    /** 单例 OkHttp（不能每次 new，会耗 fd 和线程池；3s timeout 避免 hang）*/
    private OkHttpClient bookTickerHttp;

    @PostConstruct
    public void initBookTickerHttp() {
        this.bookTickerHttp = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofMillis(2000))
                .readTimeout(Duration.ofMillis(2000))
                .callTimeout(Duration.ofMillis(3000))
                .build();
    }

    // ─── 现货 ────────────────────────────────────────────────────────────

    /**
     * 现货 IOC LIMIT BUY：价格 = ask × (1 + cap)，如未成交则拒绝，等价 MARKET 但限滑点。
     * <p>Binance 现货支持 timeInForce=IOC；BinanceTradeClient 现在的实现固定 GTC，我们把 type
     * 降级为 MARKET 让币安按最优 ask 全额成交（v2.1 简化：MARKET + basis 校验）。</p>
     * <p>TODO Sprint 5+: 扩展 BinanceTradeClient 支持 IOC，改用 IOC LIMIT。</p>
     */
    public PairOrderResult spotBuyMarket(String apiKey, String apiSecret, String symbol,
                                          BigDecimal qty, BigDecimal feeRate) {
        try {
            JSONObject resp = spotClient.placeOrder(apiKey, apiSecret, symbol,
                    "BUY", "MARKET", qty, null);
            return parseSpotResp(resp, feeRate);
        } catch (Exception ex) {
            log.warn("[SpotPerpOrderClient] spot BUY {} qty={} failed: {}", symbol, qty, ex.getMessage());
            return PairOrderResult.builder().success(false).errorMessage(ex.getMessage()).build();
        }
    }

    public PairOrderResult spotSellMarket(String apiKey, String apiSecret, String symbol,
                                           BigDecimal qty, BigDecimal feeRate) {
        try {
            JSONObject resp = spotClient.placeOrder(apiKey, apiSecret, symbol,
                    "SELL", "MARKET", qty, null);
            return parseSpotResp(resp, feeRate);
        } catch (Exception ex) {
            log.warn("[SpotPerpOrderClient] spot SELL {} qty={} failed: {}", symbol, qty, ex.getMessage());
            return PairOrderResult.builder().success(false).errorMessage(ex.getMessage()).build();
        }
    }

    // ─── 期货 USDM ───────────────────────────────────────────────────────

    /**
     * 期货 MARKET SHORT（SELL 开仓，非 reduceOnly）
     */
    public PairOrderResult perpSellMarket(String apiKey, String apiSecret, String symbol,
                                           BigDecimal qty, BigDecimal feeRate) {
        try {
            JSONObject resp = futuresClient.placeOrder(apiKey, apiSecret, symbol,
                    "SELL", "MARKET", qty, null, false, MarketType.USDM);
            return parseFuturesResp(resp, feeRate);
        } catch (Exception ex) {
            log.warn("[SpotPerpOrderClient] perp SELL {} qty={} failed: {}", symbol, qty, ex.getMessage());
            return PairOrderResult.builder().success(false).errorMessage(ex.getMessage()).build();
        }
    }

    /**
     * 期货 MARKET BUY 平仓（reduceOnly=true）
     */
    public PairOrderResult perpBuyReduceOnly(String apiKey, String apiSecret, String symbol,
                                              BigDecimal qty, BigDecimal feeRate) {
        try {
            JSONObject resp = futuresClient.placeOrder(apiKey, apiSecret, symbol,
                    "BUY", "MARKET", qty, null, true, MarketType.USDM);
            return parseFuturesResp(resp, feeRate);
        } catch (Exception ex) {
            log.warn("[SpotPerpOrderClient] perp BUY reduceOnly {} qty={} failed: {}", symbol, qty, ex.getMessage());
            return PairOrderResult.builder().success(false).errorMessage(ex.getMessage()).build();
        }
    }

    // ─── Post-only (LIMIT_MAKER / GTX) ──────────────────────────────────

    /** 现货 LIMIT_MAKER BUY @ price（post-only）*/
    public PairOrderResult spotPostOnlyBuy(String apiKey, String apiSecret, String symbol,
                                            BigDecimal qty, BigDecimal price, BigDecimal feeRate) {
        try {
            JSONObject resp = spotClient.placeOrder(apiKey, apiSecret, symbol,
                    "BUY", "LIMIT_MAKER", qty, price);
            return parseSpotResp(resp, feeRate);
        } catch (Exception ex) {
            log.warn("[SpotPerpOrderClient] spot POST-ONLY BUY {} qty={} price={} failed: {}",
                    symbol, qty, price, ex.getMessage());
            return PairOrderResult.builder().success(false).errorMessage(ex.getMessage()).build();
        }
    }

    /** 现货 LIMIT_MAKER SELL @ price（post-only）*/
    public PairOrderResult spotPostOnlySell(String apiKey, String apiSecret, String symbol,
                                             BigDecimal qty, BigDecimal price, BigDecimal feeRate) {
        try {
            JSONObject resp = spotClient.placeOrder(apiKey, apiSecret, symbol,
                    "SELL", "LIMIT_MAKER", qty, price);
            return parseSpotResp(resp, feeRate);
        } catch (Exception ex) {
            log.warn("[SpotPerpOrderClient] spot POST-ONLY SELL {} qty={} price={} failed: {}",
                    symbol, qty, price, ex.getMessage());
            return PairOrderResult.builder().success(false).errorMessage(ex.getMessage()).build();
        }
    }

    /** 期货 LIMIT+GTX SELL 开空 @ price（post-only）*/
    public PairOrderResult perpPostOnlySell(String apiKey, String apiSecret, String symbol,
                                             BigDecimal qty, BigDecimal price, BigDecimal feeRate) {
        try {
            // type=LIMIT_GTX 被 BinanceFuturesClient 解析成 LIMIT + timeInForce=GTX
            JSONObject resp = futuresClient.placeOrder(apiKey, apiSecret, symbol,
                    "SELL", "LIMIT_GTX", qty, price, false, MarketType.USDM);
            return parseFuturesResp(resp, feeRate);
        } catch (Exception ex) {
            log.warn("[SpotPerpOrderClient] perp POST-ONLY SELL {} qty={} price={} failed: {}",
                    symbol, qty, price, ex.getMessage());
            return PairOrderResult.builder().success(false).errorMessage(ex.getMessage()).build();
        }
    }

    /** 期货 LIMIT+GTX BUY reduceOnly @ price（post-only）*/
    public PairOrderResult perpPostOnlyBuyReduceOnly(String apiKey, String apiSecret, String symbol,
                                                      BigDecimal qty, BigDecimal price, BigDecimal feeRate) {
        try {
            JSONObject resp = futuresClient.placeOrder(apiKey, apiSecret, symbol,
                    "BUY", "LIMIT_GTX", qty, price, true, MarketType.USDM);
            return parseFuturesResp(resp, feeRate);
        } catch (Exception ex) {
            log.warn("[SpotPerpOrderClient] perp POST-ONLY BUY reduceOnly {} qty={} price={} failed: {}",
                    symbol, qty, price, ex.getMessage());
            return PairOrderResult.builder().success(false).errorMessage(ex.getMessage()).build();
        }
    }

    // ─── Query / Cancel（用于 DualMakerExecutor 轮询 fill 状态）──────────

    public JSONObject spotQueryOrder(String apiKey, String apiSecret, String symbol, String orderId) {
        try { return spotClient.queryOrder(apiKey, apiSecret, symbol, orderId); }
        catch (Exception ex) {
            log.debug("[SpotPerpOrderClient] spot query {} {} failed: {}", symbol, orderId, ex.getMessage());
            return null;
        }
    }
    public JSONObject perpQueryOrder(String apiKey, String apiSecret, String symbol, String orderId) {
        try { return futuresClient.queryOrder(apiKey, apiSecret, symbol, orderId, MarketType.USDM); }
        catch (Exception ex) {
            log.debug("[SpotPerpOrderClient] perp query {} {} failed: {}", symbol, orderId, ex.getMessage());
            return null;
        }
    }
    public boolean spotCancel(String apiKey, String apiSecret, String symbol, String orderId) {
        try { spotClient.cancelOrder(apiKey, apiSecret, symbol, orderId); return true; }
        catch (Exception ex) {
            log.debug("[SpotPerpOrderClient] spot cancel {} {} err={}", symbol, orderId, ex.getMessage());
            return false;
        }
    }
    public boolean perpCancel(String apiKey, String apiSecret, String symbol, String orderId) {
        try { futuresClient.cancelOrder(apiKey, apiSecret, symbol, orderId, MarketType.USDM); return true; }
        catch (Exception ex) {
            log.debug("[SpotPerpOrderClient] perp cancel {} {} err={}", symbol, orderId, ex.getMessage());
            return false;
        }
    }

    // ─── Book ticker（获取 bestBid/bestAsk 用于挂 post-only）────────────

    /** 返回 [bestBid, bestAsk]，任一为 null 表示获取失败 */
    public BigDecimal[] getSpotBookTicker(String symbol) {
        return getBookTicker(properties.getBinanceSpotBaseUrl()
                + "/api/v3/ticker/bookTicker?symbol=" + symbol);
    }
    public BigDecimal[] getPerpBookTicker(String symbol) {
        return getBookTicker(properties.getBinanceUsdmBaseUrl()
                + "/fapi/v1/ticker/bookTicker?symbol=" + symbol);
    }
    private BigDecimal[] getBookTicker(String url) {
        try {
            Request req = new Request.Builder().url(url).get().build();
            try (Response resp = bookTickerHttp.newCall(req).execute()) {
                if (!resp.isSuccessful() || resp.body() == null) return new BigDecimal[]{null, null};
                JSONObject j = com.alibaba.fastjson2.JSON.parseObject(resp.body().string());
                return new BigDecimal[]{ j.getBigDecimal("bidPrice"), j.getBigDecimal("askPrice") };
            }
        } catch (Exception ex) {
            log.warn("[SpotPerpOrderClient] bookTicker {} failed: {}", url, ex.getMessage());
            return new BigDecimal[]{null, null};
        }
    }

    // ─── 内部：response 解析 ────────────────────────────────────────────

    private PairOrderResult parseSpotResp(JSONObject resp, BigDecimal feeRate) {
        String status = resp.getString("status");
        // NEW: LIMIT_MAKER 挂单成功进入 orderbook；后续由 DualMakerExecutor 轮询确认成交
        boolean success = "FILLED".equals(status) || "PARTIALLY_FILLED".equals(status)
                || "NEW".equals(status);
        BigDecimal executedQty = resp.getBigDecimal("executedQty");
        BigDecimal cummulativeQuoteQty = resp.getBigDecimal("cummulativeQuoteQty");
        BigDecimal avgPrice = executedQty != null && executedQty.compareTo(BigDecimal.ZERO) > 0
                && cummulativeQuoteQty != null
                ? cummulativeQuoteQty.divide(executedQty, 8, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        BigDecimal notional = cummulativeQuoteQty != null ? cummulativeQuoteQty : BigDecimal.ZERO;
        BigDecimal fee = feeRate != null ? notional.multiply(feeRate) : BigDecimal.ZERO;
        return PairOrderResult.builder()
                .success(success)
                .orderId(resp.getString("orderId"))
                .clientOrderId(resp.getString("clientOrderId"))
                .executedQty(executedQty != null ? executedQty : BigDecimal.ZERO)
                .avgFillPrice(avgPrice)
                .feePaid(fee)
                .errorMessage(success ? null : ("spot status=" + status))
                .build();
    }

    private PairOrderResult parseFuturesResp(JSONObject resp, BigDecimal feeRate) {
        String status = resp.getString("status");
        boolean success = "FILLED".equals(status) || "PARTIALLY_FILLED".equals(status) || "NEW".equals(status);
        BigDecimal executedQty = resp.getBigDecimal("executedQty");
        BigDecimal avgPrice = resp.getBigDecimal("avgPrice");
        if (avgPrice == null || avgPrice.compareTo(BigDecimal.ZERO) == 0) {
            avgPrice = resp.getBigDecimal("price");
        }
        BigDecimal notional = executedQty != null && avgPrice != null
                ? executedQty.multiply(avgPrice) : BigDecimal.ZERO;
        BigDecimal fee = feeRate != null ? notional.multiply(feeRate) : BigDecimal.ZERO;
        return PairOrderResult.builder()
                .success(success)
                .orderId(resp.getString("orderId"))
                .clientOrderId(resp.getString("clientOrderId"))
                .executedQty(executedQty != null ? executedQty : BigDecimal.ZERO)
                .avgFillPrice(avgPrice != null ? avgPrice : BigDecimal.ZERO)
                .feePaid(fee)
                .errorMessage(success ? null : ("perp status=" + status))
                .build();
    }
}

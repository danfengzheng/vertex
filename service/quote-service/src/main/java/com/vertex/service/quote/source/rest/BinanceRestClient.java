package com.vertex.service.quote.source.rest;

import com.vertex.model.entity.quote.KLine;
import com.vertex.model.entity.quote.KLineInterval;
import com.vertex.service.quote.converter.KLineConverter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.util.Collections;
import java.util.List;

/**
 * 币安 REST K线客户端
 * <p>
 * 调用币安 REST API 获取历史 K线数据：
 * GET /api/v3/klines?symbol=BTCUSDT&interval=1m&startTime=xxx&endTime=xxx&limit=500
 */
@Slf4j
@RequiredArgsConstructor
public class BinanceRestClient implements KLineRestClient {

    private static final String DEFAULT_API_URL = "https://api.binance.com";

    private final OkHttpClient httpClient;
    private final KLineConverter klineConverter;
    private final String apiUrl;

    public BinanceRestClient(OkHttpClient httpClient, KLineConverter klineConverter) {
        this(httpClient, klineConverter, DEFAULT_API_URL);
    }

    @Override
    public String exchangeCode() {
        return "binance";
    }

    @Override
    public List<KLine> fetchKLines(String symbol, KLineInterval interval,
                                   Long startTime, Long endTime, int limit) {
        try {
            String url = buildUrl(symbol, interval, startTime, endTime, limit);
            Request request = new Request.Builder().url(url).get().build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    log.error("[Binance REST] Request failed, code: {}, url: {}", response.code(), url);
                    return Collections.emptyList();
                }

                ResponseBody body = response.body();
                if (body == null) {
                    return Collections.emptyList();
                }

                String rawData = body.string();
                // 统一交易对格式
                return klineConverter.convertBatch(symbol, interval, rawData);
            }
        } catch (Exception e) {
            log.error("[Binance REST] Failed to fetch KLines for {}:{}", symbol, interval.getCode(), e);
            return Collections.emptyList();
        }
    }

    /**
     * 构建币安 REST URL
     */
    private String buildUrl(String symbol, KLineInterval interval,
                            Long startTime, Long endTime, int limit) {
        // BTC-USDT → BTCUSDT
        String binanceSymbol = symbol.replace("-", "").toUpperCase();

        StringBuilder sb = new StringBuilder(apiUrl)
                .append("/api/v3/klines?symbol=").append(binanceSymbol)
                .append("&interval=").append(interval.getCode())
                .append("&limit=").append(Math.min(limit, 1000));

        if (startTime != null) {
            sb.append("&startTime=").append(startTime);
        }
        if (endTime != null) {
            sb.append("&endTime=").append(endTime);
        }
        return sb.toString();
    }
}

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
 * OKX REST K线客户端
 * <p>
 * 调用 OKX REST API 获取历史 K线数据：
 * GET /api/v5/market/candles?instId=BTC-USDT&bar=1m&before=xxx&after=xxx&limit=100
 */
@Slf4j
@RequiredArgsConstructor
public class OkxRestClient implements KLineRestClient {

    private static final String DEFAULT_API_URL = "https://www.okx.com";

    private final OkHttpClient httpClient;
    private final KLineConverter klineConverter;
    private final String apiUrl;

    public OkxRestClient(OkHttpClient httpClient, KLineConverter klineConverter) {
        this(httpClient, klineConverter, DEFAULT_API_URL);
    }

    @Override
    public String exchangeCode() {
        return "okx";
    }

    @Override
    public List<KLine> fetchKLines(String symbol, KLineInterval interval,
                                   Long startTime, Long endTime, int limit) {
        try {
            String url = buildUrl(symbol, interval, startTime, endTime, limit);
            Request request = new Request.Builder().url(url).get().build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    log.error("[OKX REST] Request failed, code: {}, url: {}", response.code(), url);
                    return Collections.emptyList();
                }

                ResponseBody body = response.body();
                if (body == null) {
                    return Collections.emptyList();
                }

                String rawData = body.string();
                return klineConverter.convertBatch(symbol, interval, rawData);
            }
        } catch (Exception e) {
            log.error("[OKX REST] Failed to fetch KLines for {}:{}", symbol, interval.getCode(), e);
            return Collections.emptyList();
        }
    }

    /**
     * 构建 OKX REST URL
     * <p>
     * OKX bar 参数格式：1m, 3m, 5m, 15m, 30m, 1H, 2H, 4H, 6H, 12H, 1D, 3D, 1W, 1M
     * 注意 OKX 小时级别以上使用大写
     */
    private String buildUrl(String symbol, KLineInterval interval,
                            Long startTime, Long endTime, int limit) {
        String bar = convertToOkxBar(interval);

        StringBuilder sb = new StringBuilder(apiUrl)
                .append("/api/v5/market/candles?instId=").append(symbol)
                .append("&bar=").append(bar)
                .append("&limit=").append(Math.min(limit, 300));  // OKX 最大 300

        // OKX 使用 before/after（时间戳ms），方向与 Binance 相反
        if (endTime != null) {
            sb.append("&before=").append(endTime);
        }
        if (startTime != null) {
            sb.append("&after=").append(startTime);
        }
        return sb.toString();
    }

    /**
     * 将统一 KLineInterval 转换为 OKX bar 参数
     */
    private String convertToOkxBar(KLineInterval interval) {
        return switch (interval) {
            case M1 -> "1m";
            case M3 -> "3m";
            case M5 -> "5m";
            case M15 -> "15m";
            case M30 -> "30m";
            case H1 -> "1H";
            case H2 -> "2H";
            case H4 -> "4H";
            case H6 -> "6Hutc";
            case H8 -> "8Hutc";
            case H12 -> "12Hutc";
            case D1 -> "1Dutc";
            case D3 -> "3Dutc";
            case W1 -> "1Wutc";
            case MN1 -> "1Mutc";
        };
    }
}

package com.vertex.service.chain.source.bnb;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Binance Alpha 代币列表 HTTP 客户端
 * <p>
 * 接口文档（公开，无需 API Key）：
 * <pre>
 * GET https://www.binance.com/bapi/defi/v1/public/wallet-direct/buw/wallet/cex/alpha/all/token/list
 * 无请求参数，一次性返回所有 Alpha 代币。
 *
 * 响应示例：
 * {
 *   "code": "000000",
 *   "success": true,
 *   "data": [
 *     {
 *       "tokenId": 175,
 *       "alphaId": "ALPHA_175",
 *       "symbol": "gorilla",
 *       "name": "Gorilla",
 *       "chainId": "56",
 *       "chainName": "BSC",
 *       "contractAddress": "0x...",
 *       "price": "0.0012",
 *       "percentChange24h": "8.5",
 *       "marketCap": "1200000",
 *       "volume24h": "500000",
 *       "liquidity": "150000",
 *       "holders": 3200,
 *       "listingTime": 1710000000000
 *     }
 *   ]
 * }
 * </pre>
 */
@Slf4j
public class BinanceAlphaClient {

    private static final String TOKEN_LIST_PATH =
            "/bapi/defi/v1/public/wallet-direct/buw/wallet/cex/alpha/all/token/list";

    private final OkHttpClient httpClient;
    private final String baseUrl;

    public BinanceAlphaClient(OkHttpClient httpClient, String baseUrl) {
        this.httpClient = httpClient;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    /**
     * 获取所有 Binance Alpha 代币（接口一次性返回全量，无分页）
     *
     * @return 代币 JSON 对象列表，失败返回空列表
     */
    public List<JSONObject> fetchAllTokens() {
        String url = baseUrl + TOKEN_LIST_PATH;
        try {
            Request request = new Request.Builder()
                    .url(url)
                    .addHeader("Accept", "application/json")
                    .addHeader("User-Agent", "Mozilla/5.0 (compatible; VertexBot/1.0)")
                    .get()
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    log.warn("[BinanceAlpha] HTTP {} for {}", response.code(), url);
                    return Collections.emptyList();
                }
                ResponseBody rb = response.body();
                if (rb == null) return Collections.emptyList();

                JSONObject resp = JSON.parseObject(rb.string());
                if (resp == null || !"000000".equals(resp.getString("code"))) {
                    log.warn("[BinanceAlpha] API returned non-success response: {}", resp);
                    return Collections.emptyList();
                }

                // data 字段为 JSONArray（全量列表，无分页）
                JSONArray data = resp.getJSONArray("data");
                if (data == null || data.isEmpty()) return Collections.emptyList();

                List<JSONObject> result = new ArrayList<>(data.size());
                for (int i = 0; i < data.size(); i++) {
                    result.add(data.getJSONObject(i));
                }
                return result;
            }
        } catch (Exception e) {
            log.warn("[BinanceAlpha] fetchAllTokens failed: {}", e.getMessage());
            return Collections.emptyList();
        }
    }
}

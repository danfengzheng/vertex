package com.vertex.service.chain.source.bnb;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Binance Alpha 代币列表 HTTP 客户端
 * <p>
 * Binance Alpha 是币安针对早期阶段 Web3 项目的展示平台，
 * 上榜项目具备潜在上所可能性，是山寨币筛选的重要参考来源。
 * <p>
 * 接口文档（公开，无需 API Key）：
 * <pre>
 * POST https://www.binance.com/bapi/defi/v1/public/defi/token/page
 * Body: { "pageIndex": 1, "pageSize": 50 }
 *
 * 响应示例：
 * {
 *   "code": "000000",
 *   "data": {
 *     "rows": [
 *       {
 *         "tokenContractAddress": "0x...",
 *         "tokenName": "Example Token",
 *         "symbol": "EXP",
 *         "network": "BSC",
 *         "price": "0.0012",
 *         "priceChange24h": "8.5",
 *         "marketCap": "1200000",
 *         "volume24h": "500000",
 *         "liquidity": "150000",
 *         "holderCount": 3200
 *       }
 *     ],
 *     "total": 100
 *   }
 * }
 * </pre>
 */
@Slf4j
public class BinanceAlphaClient {

    private static final MediaType JSON_MEDIA_TYPE = MediaType.get("application/json; charset=utf-8");
    private static final String TOKEN_PAGE_PATH = "/bapi/defi/v1/public/defi/token/page";

    private final OkHttpClient httpClient;
    private final String baseUrl;

    public BinanceAlphaClient(OkHttpClient httpClient, String baseUrl) {
        this.httpClient = httpClient;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    /**
     * 分页获取 Binance Alpha 代币列表
     *
     * @param pageIndex 页码（从 1 开始）
     * @param pageSize  每页数量
     * @return 代币 JSON 对象列表，失败返回空列表
     */
    public List<JSONObject> fetchTokenPage(int pageIndex, int pageSize) {
        try {
            String url = baseUrl + TOKEN_PAGE_PATH;
            JSONObject body = new JSONObject();
            body.put("pageIndex", pageIndex);
            body.put("pageSize", pageSize);

            Request request = new Request.Builder()
                    .url(url)
                    .addHeader("Accept", "application/json")
                    .addHeader("Content-Type", "application/json")
                    .addHeader("User-Agent", "Mozilla/5.0 (compatible; VertexBot/1.0)")
                    .post(RequestBody.create(body.toJSONString(), JSON_MEDIA_TYPE))
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
                    log.warn("[BinanceAlpha] API returned non-success code: {}", resp);
                    return Collections.emptyList();
                }

                JSONObject data = resp.getJSONObject("data");
                if (data == null) return Collections.emptyList();

                JSONArray rows = data.getJSONArray("rows");
                if (rows == null || rows.isEmpty()) return Collections.emptyList();

                List<JSONObject> result = new ArrayList<>(rows.size());
                for (int i = 0; i < rows.size(); i++) {
                    result.add(rows.getJSONObject(i));
                }
                return result;
            }
        } catch (Exception e) {
            log.warn("[BinanceAlpha] fetchTokenPage failed: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 获取全部 Binance Alpha 代币（自动翻页，最多 maxTokens 个）
     */
    public List<JSONObject> fetchAllTokens(int pageSize, int maxTokens) {
        List<JSONObject> all = new ArrayList<>();
        int page = 1;
        while (all.size() < maxTokens) {
            List<JSONObject> pageData = fetchTokenPage(page, pageSize);
            if (pageData.isEmpty()) break;
            all.addAll(pageData);
            if (pageData.size() < pageSize) break; // 最后一页
            page++;
        }
        return all.size() > maxTokens ? all.subList(0, maxTokens) : all;
    }
}

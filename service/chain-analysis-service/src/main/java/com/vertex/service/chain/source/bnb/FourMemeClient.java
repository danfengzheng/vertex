package com.vertex.service.chain.source.bnb;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.util.Collections;
import java.util.List;
import java.util.ArrayList;

/**
 * Four.meme 一级市场数据客户端
 * <p>
 * Four.meme 是 Pump.fun 在 BSC 上的等价平台：
 * <ul>
 *   <li>代币从 bonding curve 阶段开始，无需 DEX 上市费用</li>
 *   <li>bonding curve 积累约 $28,000 后自动毕业，LP 打入 PancakeSwap</li>
 *   <li>API 无需 Key，免费访问</li>
 * </ul>
 */
@Slf4j
public class FourMemeClient {

    private static final String BASE_URL = "https://four.meme";

    private final OkHttpClient httpClient;

    public FourMemeClient(OkHttpClient httpClient) {
        this.httpClient = httpClient;
    }

    /**
     * 获取最新的一级市场代币列表（bonding curve 阶段，未上 PancakeSwap）
     *
     * @param limit 返回条数
     * @return 代币 JSON 列表，字段说明见 {@link BnbChainDataSource#buildFromFourMeme}
     */
    public List<JSONObject> fetchNewTokens(int limit) {
        // Four.meme 的公开 API 端点（通过官网 XHR 逆向）
        String url = BASE_URL + "/meme-launchpad/token/list?pageNo=1&pageSize="
                + Math.min(limit, 100)
                + "&orderBy=0";  // orderBy=0 = 最新创建
        try {
            JSONObject resp = getJson(url);
            if (resp == null) return Collections.emptyList();

            // 响应结构：{ "code":0, "data":{ "tokenList":[...] } }
            JSONObject data = resp.getJSONObject("data");
            if (data == null) return Collections.emptyList();

            JSONArray arr = data.getJSONArray("tokenList");
            if (arr == null || arr.isEmpty()) return Collections.emptyList();

            List<JSONObject> result = new ArrayList<>();
            for (int i = 0; i < arr.size(); i++) {
                result.add(arr.getJSONObject(i));
            }
            log.debug("[4meme] Fetched {} tokens", result.size());
            return result;
        } catch (Exception e) {
            log.warn("[4meme] fetchNewTokens failed: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    // ─── 内部 HTTP ──────────────────────────────────────────

    private JSONObject getJson(String url) {
        Request request = new Request.Builder()
                .url(url)
                .addHeader("Accept", "application/json")
                .addHeader("Origin", BASE_URL)
                .addHeader("Referer", BASE_URL + "/")
                .get()
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                log.debug("[4meme] HTTP {}: {}", response.code(), url);
                return null;
            }
            ResponseBody rb = response.body();
            return rb != null ? JSON.parseObject(rb.string()) : null;
        } catch (Exception e) {
            log.warn("[4meme] Request failed [{}]: {}", url, e.getMessage());
            return null;
        }
    }
}

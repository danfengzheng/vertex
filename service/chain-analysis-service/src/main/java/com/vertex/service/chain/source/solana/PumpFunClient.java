package com.vertex.service.chain.source.solana;

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
 * Pump.fun 新币列表 API 客户端
 * <p>
 * 获取 Pump.fun 平台最新上线的 Solana meme 代币列表。
 * Pump.fun Frontend API 无需 Key。
 */
@Slf4j
public class PumpFunClient {

    private final OkHttpClient httpClient;
    private final String apiUrl;

    public PumpFunClient(OkHttpClient httpClient, String apiUrl) {
        this.httpClient = httpClient;
        this.apiUrl = apiUrl;
    }

    /**
     * 获取最新上线代币列表
     *
     * @param limit 返回数量（最多 50）
     */
    public List<JSONObject> fetchLatestTokens(int limit) {
        try {
            String url = apiUrl + "/coins?offset=0&limit=" + Math.min(limit, 50) + "&sort=created_timestamp&order=DESC&includeNsfw=false";
            JSONArray arr = getJsonArray(url);
            if (arr == null) return Collections.emptyList();
            List<JSONObject> result = new ArrayList<>();
            for (int i = 0; i < arr.size(); i++) {
                result.add(arr.getJSONObject(i));
            }
            return result;
        } catch (Exception e) {
            log.warn("[PumpFun] fetchLatestTokens failed: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 查询单个代币详情
     *
     * @param mintAddress 代币 Mint 地址
     */
    public JSONObject fetchTokenDetail(String mintAddress) {
        try {
            String url = apiUrl + "/coins/" + mintAddress;
            return getJson(url);
        } catch (Exception e) {
            log.debug("[PumpFun] fetchTokenDetail failed for {}: {}", mintAddress, e.getMessage());
            return null;
        }
    }

    /**
     * 判断代币是否通过 Pump.fun 上市（已毕业到 Raydium）
     */
    public boolean isGraduated(String mintAddress) {
        JSONObject detail = fetchTokenDetail(mintAddress);
        if (detail == null) return false;
        return Boolean.TRUE.equals(detail.getBoolean("complete"));
    }

    // ─── 内部方法 ──────────────────────────────────────────

    private JSONObject getJson(String url) {
        Request request = new Request.Builder()
                .url(url)
                .addHeader("Accept", "application/json")
                .get()
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) return null;
            ResponseBody rb = response.body();
            return rb != null ? JSON.parseObject(rb.string()) : null;
        } catch (Exception e) {
            log.warn("[PumpFun] Request failed: {}", e.getMessage());
            return null;
        }
    }

    private JSONArray getJsonArray(String url) {
        Request request = new Request.Builder()
                .url(url)
                .addHeader("Accept", "application/json")
                .get()
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) return null;
            ResponseBody rb = response.body();
            if (rb == null) return null;
            String bodyStr = rb.string();
            // API 返回可能是数组或包装对象
            if (bodyStr.startsWith("[")) {
                return JSON.parseArray(bodyStr);
            }
            JSONObject obj = JSON.parseObject(bodyStr);
            return obj != null ? obj.getJSONArray("data") : null;
        } catch (Exception e) {
            log.warn("[PumpFun] Array request failed: {}", e.getMessage());
            return null;
        }
    }
}

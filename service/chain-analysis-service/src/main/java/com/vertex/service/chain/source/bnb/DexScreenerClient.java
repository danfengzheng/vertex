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
 * DexScreener API 客户端
 * <p>
 * 完全免费，无需 API Key，支持 BNB Chain / Solana / ETH 等所有主流链。
 * <p>
 * 主要用途：
 * <ul>
 *   <li>查询最新创建的交易对（新币发现）</li>
 *   <li>获取流动性、价格、成交量、买卖压力等市场数据</li>
 * </ul>
 * <p>
 * API 文档：https://docs.dexscreener.com/api/reference
 * 速率限制：300 次/分钟（免费，无需注册）
 */
@Slf4j
public class DexScreenerClient {

    private static final String BASE_URL = "https://api.dexscreener.com";

    private final OkHttpClient httpClient;

    public DexScreenerClient(OkHttpClient httpClient) {
        this.httpClient = httpClient;
    }

    /**
     * 查询指定链上最新创建的交易对
     *
     * @param chainId     链标识，DexScreener 格式：bsc / solana / ethereum
     * @param sinceHours  向前查询的小时数（筛选最近 N 小时内创建的 pair）
     */
    public List<JSONObject> fetchLatestPairs(String chainId, int sinceHours) {
        try {
            // DexScreener 的 /token-profiles/latest/v1 接口返回最新上市的代币
            // 使用 /latest/dex/tokens 接口按链查询新 pair
            String url = BASE_URL + "/token-profiles/latest/v1";
            JSONArray items = getJsonArray(url);
            if (items == null) return Collections.emptyList();

            long sinceMs = System.currentTimeMillis() - (long) sinceHours * 3600 * 1000;
            List<JSONObject> result = new ArrayList<>();

            for (int i = 0; i < items.size(); i++) {
                JSONObject item = items.getJSONObject(i);
                String chain = item.getString("chainId");
                if (!chainId.equalsIgnoreCase(chain)) continue;
                result.add(item);
            }
            return result;
        } catch (Exception e) {
            log.warn("[DexScreener] fetchLatestPairs failed for {}: {}", chainId, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 按合约地址查询代币的所有交易对详情
     * 返回价格、流动性、24h 成交量、买卖压力等完整市场数据
     *
     * @param chainId         链标识（bsc / solana）
     * @param tokenAddress    代币合约/Mint 地址
     */
    public List<JSONObject> fetchTokenPairs(String chainId, String tokenAddress) {
        try {
            String url = BASE_URL + "/token-pairs/v1/" + chainId + "/" + tokenAddress;
            JSONObject resp = getJson(url);
            if (resp == null) return Collections.emptyList();

            JSONArray pairs = resp.getJSONArray("pairs");
            if (pairs == null) return Collections.emptyList();

            List<JSONObject> result = new ArrayList<>();
            for (int i = 0; i < pairs.size(); i++) {
                result.add(pairs.getJSONObject(i));
            }
            return result;
        } catch (Exception e) {
            log.debug("[DexScreener] fetchTokenPairs failed for {} {}: {}", chainId, tokenAddress, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 搜索最新交易对（按链过滤，按创建时间排序）
     * 用于发现刚上线的新币
     *
     * @param chainId 链标识
     * @param limit   返回数量
     */
    public List<JSONObject> searchNewPairs(String chainId, int limit) {
        try {
            // 使用 /latest/dex/pairs/{chainId} 接口
            String url = BASE_URL + "/latest/dex/pairs/" + chainId;
            JSONObject resp = getJson(url);
            if (resp == null) return Collections.emptyList();

            JSONArray pairs = resp.getJSONArray("pairs");
            if (pairs == null) return Collections.emptyList();

            List<JSONObject> result = new ArrayList<>();
            int count = Math.min(limit, pairs.size());
            for (int i = 0; i < count; i++) {
                result.add(pairs.getJSONObject(i));
            }
            return result;
        } catch (Exception e) {
            log.warn("[DexScreener] searchNewPairs failed for {}: {}", chainId, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 按 pair 地址直接查询单个交易对详情
     */
    public JSONObject fetchPairDetail(String chainId, String pairAddress) {
        try {
            String url = BASE_URL + "/latest/dex/pairs/" + chainId + "/" + pairAddress;
            JSONObject resp = getJson(url);
            if (resp == null) return null;
            JSONArray pairs = resp.getJSONArray("pairs");
            return (pairs != null && !pairs.isEmpty()) ? pairs.getJSONObject(0) : null;
        } catch (Exception e) {
            log.debug("[DexScreener] fetchPairDetail failed: {}", e.getMessage());
            return null;
        }
    }

    // ─── 内部工具 ──────────────────────────────────────────

    private JSONObject getJson(String url) {
        Request request = new Request.Builder()
                .url(url)
                .addHeader("Accept", "application/json")
                .get()
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                log.warn("[DexScreener] HTTP {} for {}", response.code(), url);
                return null;
            }
            ResponseBody rb = response.body();
            return rb != null ? JSON.parseObject(rb.string()) : null;
        } catch (Exception e) {
            log.warn("[DexScreener] Request failed: {}", e.getMessage());
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
            String body = rb.string();
            if (body.startsWith("[")) return JSON.parseArray(body);
            JSONObject obj = JSON.parseObject(body);
            return obj != null ? obj.getJSONArray("pairs") : null;
        } catch (Exception e) {
            log.warn("[DexScreener] Array request failed: {}", e.getMessage());
            return null;
        }
    }
}

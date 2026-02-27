package com.vertex.service.chain.source.bnb;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * BSCScan REST API 客户端
 * <p>
 * 用于查询 BNB Chain 上的合约信息、持有者数量、交易历史等数据。
 * 免费 API Key 额度：5次/秒。
 */
@Slf4j
public class BscScanClient {

    private final OkHttpClient httpClient;
    private final String apiUrl;
    private final String apiKey;

    public BscScanClient(OkHttpClient httpClient, String apiUrl, String apiKey) {
        this.httpClient = httpClient;
        this.apiUrl = apiUrl;
        this.apiKey = apiKey;
    }

    /**
     * 查询代币持有者数量（通过 tokenholderlist 接口估算 offset 方式）
     * 注意：BSCScan 免费 API 不直接提供 holderCount，通过 tokeninfo 接口获取。
     */
    public int getHolderCount(String contractAddress) {
        try {
            String url = apiUrl + "?module=token&action=tokeninfo"
                    + "&contractaddress=" + contractAddress
                    + "&apikey=" + apiKey;
            JSONObject result = getJson(url);
            if (result == null) return 0;
            JSONArray items = result.getJSONArray("result");
            if (items == null || items.isEmpty()) return 0;
            JSONObject info = items.getJSONObject(0);
            String holders = info.getString("holdersCount");
            if (holders == null || holders.isBlank()) return 0;
            return Integer.parseInt(holders.replace(",", "").trim());
        } catch (Exception e) {
            log.debug("[BSCScan] getHolderCount failed for {}: {}", contractAddress, e.getMessage());
            return 0;
        }
    }

    /**
     * 查询最近 1h 内的交易笔数（BEP-20 Transfer 事件近似统计）
     */
    public int getTxCount1h(String contractAddress) {
        try {
            long now = System.currentTimeMillis() / 1000;
            long from = now - 3600;
            String url = apiUrl + "?module=account&action=tokentx"
                    + "&contractaddress=" + contractAddress
                    + "&startblock=0&endblock=99999999"
                    + "&sort=desc&offset=100&page=1"
                    + "&apikey=" + apiKey;
            JSONObject result = getJson(url);
            if (result == null) return 0;
            JSONArray txList = result.getJSONArray("result");
            if (txList == null) return 0;
            int count = 0;
            for (int i = 0; i < txList.size(); i++) {
                JSONObject tx = txList.getJSONObject(i);
                long ts = tx.getLongValue("timeStamp");
                if (ts >= from) count++;
                else break; // 降序，超出时间窗口即可停止
            }
            return count;
        } catch (Exception e) {
            log.debug("[BSCScan] getTxCount1h failed for {}: {}", contractAddress, e.getMessage());
            return 0;
        }
    }

    /**
     * 查询合约是否已验证
     */
    public boolean isContractVerified(String contractAddress) {
        try {
            String url = apiUrl + "?module=contract&action=getabi"
                    + "&address=" + contractAddress
                    + "&apikey=" + apiKey;
            JSONObject result = getJson(url);
            if (result == null) return false;
            return "1".equals(result.getString("status"));
        } catch (Exception e) {
            log.debug("[BSCScan] isContractVerified failed for {}: {}", contractAddress, e.getMessage());
            return false;
        }
    }

    /**
     * 查询代币基本信息（名称、符号、精度）
     */
    public JSONObject getTokenInfo(String contractAddress) {
        try {
            String url = apiUrl + "?module=token&action=tokeninfo"
                    + "&contractaddress=" + contractAddress
                    + "&apikey=" + apiKey;
            JSONObject result = getJson(url);
            if (result == null) return null;
            JSONArray items = result.getJSONArray("result");
            if (items == null || items.isEmpty()) return null;
            return items.getJSONObject(0);
        } catch (Exception e) {
            log.debug("[BSCScan] getTokenInfo failed for {}: {}", contractAddress, e.getMessage());
            return null;
        }
    }

    // ─── 内部工具方法 ─────────────────────────────────────

    private JSONObject getJson(String url) {
        Request request = new Request.Builder().url(url).get().build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                log.warn("[BSCScan] HTTP error {}, url: {}", response.code(), url);
                return null;
            }
            ResponseBody body = response.body();
            if (body == null) return null;
            return JSON.parseObject(body.string());
        } catch (Exception e) {
            log.warn("[BSCScan] Request failed: {}", e.getMessage());
            return null;
        }
    }
}

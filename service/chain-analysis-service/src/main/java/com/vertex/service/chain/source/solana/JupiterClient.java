package com.vertex.service.chain.source.solana;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.math.BigDecimal;

/**
 * Jupiter 价格 API 客户端
 * <p>
 * 获取 Solana 代币的 USD 价格和流动性数据。
 * <p>
 * ⚠️ 注意：此类目前未被任何 DataSource 实例化（SolanaChainDataSource 未使用 Jupiter）。
 * 若未来需要启用，请先更新以下废弃端点：
 * <ul>
 *   <li>旧（废弃）: {@code https://price.jup.ag/v4} — Jupiter 无 v4，price.jup.ag 域名已废弃</li>
 *   <li>新: {@code https://lite-api.jup.ag/price/v2}（免费，无需 Key）
 *       或 {@code https://api.jup.ag/price/v3}（需 API Key，推荐）</li>
 *   <li>Token 列表: 原 {@code token.jup.ag/strict} 已废弃 → 改为 {@code https://api.jup.ag/tokens/v2}</li>
 * </ul>
 */
@Slf4j
public class JupiterClient {

    private final OkHttpClient httpClient;
    private final String apiUrl;

    public JupiterClient(OkHttpClient httpClient, String apiUrl) {
        this.httpClient = httpClient;
        this.apiUrl = apiUrl;
    }

    /**
     * 查询代币 USD 价格
     *
     * @param mintAddress 代币 Mint 地址
     * @return USD 价格，null 表示未找到
     */
    public BigDecimal getPrice(String mintAddress) {
        try {
            String url = apiUrl + "/price?ids=" + mintAddress;
            JSONObject resp = getJson(url);
            if (resp == null) return null;
            JSONObject data = resp.getJSONObject("data");
            if (data == null) return null;
            JSONObject tokenData = data.getJSONObject(mintAddress);
            if (tokenData == null) return null;
            return tokenData.getBigDecimal("price");
        } catch (Exception e) {
            log.debug("[Jupiter] getPrice failed for {}: {}", mintAddress, e.getMessage());
            return null;
        }
    }

    /**
     * 批量查询代币价格（以逗号分隔的 mint 地址列表）
     *
     * @param mintAddresses 逗号分隔的 Mint 地址
     */
    public JSONObject getPrices(String mintAddresses) {
        try {
            String url = apiUrl + "/price?ids=" + mintAddresses;
            JSONObject resp = getJson(url);
            return resp != null ? resp.getJSONObject("data") : null;
        } catch (Exception e) {
            log.debug("[Jupiter] getPrices failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 查询代币在 Jupiter 的额外信息（是否支持兑换 = 流动性较好）
     */
    public boolean isTokenListed(String mintAddress) {
        try {
            String url = "https://token.jup.ag/strict";
            // Jupiter strict list 不经常变，实际生产中可缓存
            // 此处简化：若 price 不为 null，则认为已上市
            return getPrice(mintAddress) != null;
        } catch (Exception e) {
            return false;
        }
    }

    // ─── 内部方法 ──────────────────────────────────────────

    private JSONObject getJson(String url) {
        Request request = new Request.Builder().url(url).get().build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                log.warn("[Jupiter] HTTP error {}", response.code());
                return null;
            }
            ResponseBody rb = response.body();
            return rb != null ? JSON.parseObject(rb.string()) : null;
        } catch (Exception e) {
            log.warn("[Jupiter] Request failed: {}", e.getMessage());
            return null;
        }
    }
}

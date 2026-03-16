package com.vertex.service.chain.source.bnb;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * PancakeSwap V3 Subgraph GraphQL 客户端
 * <p>
 * ⚠️ 注意：此类目前未被任何 DataSource 实例化（BnbChainDataSource 已改为 Four.meme + DexScreener）。
 * 若未来需要启用，请先更新以下废弃端点：
 * <ul>
 *   <li>旧（废弃）: {@code https://api.thegraph.com/subgraphs/name/pancakeswap/exchange-v3-bsc}
 *       — The Graph 托管服务已于 2024-06-12 停止</li>
 *   <li>新（需 Graph API Key）: {@code https://gateway.thegraph.com/api/{api-key}/subgraphs/id/Hv1GncLY5docZoGtXjo4kwbTvxm3MAhVZqBZE4sUT9eZ}</li>
 *   <li>或使用 Subgraph Studio 测试端点: {@code https://api.studio.thegraph.com/query/{id}/exchange-v3-bsc/v0.0.0}</li>
 *   <li>官方文档: https://developer.pancakeswap.finance/apis/subgraph</li>
 * </ul>
 */
@Slf4j
public class PancakeSwapClient {

    private static final MediaType JSON_MEDIA = MediaType.parse("application/json; charset=utf-8");

    private final OkHttpClient httpClient;
    private final String graphUrl;

    public PancakeSwapClient(OkHttpClient httpClient, String graphUrl) {
        this.httpClient = httpClient;
        this.graphUrl = graphUrl;
    }

    /**
     * 查询最近创建的交易对列表
     *
     * @param sinceTimestamp Unix 秒时间戳，查询此时间之后创建的交易对
     * @param limit          最多返回数量
     */
    public List<JSONObject> fetchNewPools(long sinceTimestamp, int limit) {
        String query = """
                {
                  pools(
                    first: %d
                    orderBy: createdAtTimestamp
                    orderDirection: desc
                    where: { createdAtTimestamp_gte: "%d" }
                  ) {
                    id
                    token0 { id symbol name decimals }
                    token1 { id symbol name decimals }
                    createdAtTimestamp
                    totalValueLockedUSD
                    volumeUSD
                    token0Price
                    token1Price
                    txCount
                  }
                }
                """.formatted(Math.min(limit, 100), sinceTimestamp);

        JSONObject response = postGraphQL(query);
        if (response == null) return Collections.emptyList();

        JSONObject data = response.getJSONObject("data");
        if (data == null) return Collections.emptyList();

        JSONArray pools = data.getJSONArray("pools");
        if (pools == null) return Collections.emptyList();

        List<JSONObject> result = new ArrayList<>();
        for (int i = 0; i < pools.size(); i++) {
            result.add(pools.getJSONObject(i));
        }
        return result;
    }

    /**
     * 查询单个交易对的详细信息
     *
     * @param poolId 交易对合约地址
     */
    public JSONObject fetchPoolDetail(String poolId) {
        String query = """
                {
                  pool(id: "%s") {
                    id
                    token0 { id symbol name decimals }
                    token1 { id symbol name decimals }
                    totalValueLockedUSD
                    volumeUSD
                    token0Price
                    token1Price
                    txCount
                    createdAtTimestamp
                    liquidity
                  }
                }
                """.formatted(poolId.toLowerCase());

        JSONObject response = postGraphQL(query);
        if (response == null) return null;
        JSONObject data = response.getJSONObject("data");
        return data != null ? data.getJSONObject("pool") : null;
    }

    // ─── 内部方法 ──────────────────────────────────────────

    private JSONObject postGraphQL(String query) {
        String body = JSON.toJSONString(new JSONObject().fluentPut("query", query));
        Request request = new Request.Builder()
                .url(graphUrl)
                .post(RequestBody.create(body, JSON_MEDIA))
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                log.warn("[PancakeSwap] GraphQL HTTP error: {}", response.code());
                return null;
            }
            ResponseBody rb = response.body();
            if (rb == null) return null;
            return JSON.parseObject(rb.string());
        } catch (Exception e) {
            log.warn("[PancakeSwap] GraphQL request failed: {}", e.getMessage());
            return null;
        }
    }
}

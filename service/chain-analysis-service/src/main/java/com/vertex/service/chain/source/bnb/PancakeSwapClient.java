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
 * 通过 The Graph 查询 BNB Chain 上最新创建的 PancakeSwap V3 交易对，
 * 获取流动性、交易量、价格等市场数据。
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

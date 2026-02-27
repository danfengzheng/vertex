package com.vertex.service.chain.source.solana;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;

import java.util.Collections;
import java.util.List;
import java.util.ArrayList;

/**
 * Helius RPC 客户端
 * <p>
 * 调用 Helius API 获取 Solana 代币的链上数据，包括持有者信息、代币元数据等。
 * Dev tier 免费，有请求速率限制。
 */
@Slf4j
public class HeliusClient {

    private static final MediaType JSON_MEDIA = MediaType.parse("application/json; charset=utf-8");

    private final OkHttpClient httpClient;
    private final String rpcUrl;
    private final String apiKey;

    public HeliusClient(OkHttpClient httpClient, String rpcUrl, String apiKey) {
        this.httpClient = httpClient;
        this.rpcUrl = rpcUrl;
        this.apiKey = apiKey;
    }

    /**
     * 查询 SPL 代币的持有者账户数量
     *
     * @param mintAddress 代币 Mint 地址
     */
    public int getTokenHolderCount(String mintAddress) {
        try {
            // 使用 getProgramAccounts 查询 Token Program 下该 mint 的账户数
            String body = """
                    {"jsonrpc":"2.0","id":1,"method":"getProgramAccounts","params":[
                      "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA",
                      {"encoding":"base64","filters":[
                        {"dataSize":165},
                        {"memcmp":{"offset":0,"bytes":"%s"}}
                      ]}
                    ]}
                    """.formatted(mintAddress);
            JSONObject resp = postRpc(body);
            if (resp == null) return 0;
            JSONArray result = resp.getJSONArray("result");
            return result != null ? result.size() : 0;
        } catch (Exception e) {
            log.debug("[Helius] getTokenHolderCount failed for {}: {}", mintAddress, e.getMessage());
            return 0;
        }
    }

    /**
     * 查询代币元数据（名称、符号、精度）
     *
     * @param mintAddress 代币 Mint 地址
     */
    public JSONObject getTokenMetadata(String mintAddress) {
        try {
            String url = rpcUrl + "/?api-key=" + apiKey;
            String body = """
                    {"jsonrpc":"2.0","id":1,"method":"getAsset","params":{"id":"%s"}}
                    """.formatted(mintAddress);
            Request request = new Request.Builder()
                    .url(url)
                    .post(RequestBody.create(body, JSON_MEDIA))
                    .build();
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) return null;
                ResponseBody rb = response.body();
                if (rb == null) return null;
                JSONObject resp = JSON.parseObject(rb.string());
                return resp.getJSONObject("result");
            }
        } catch (Exception e) {
            log.debug("[Helius] getTokenMetadata failed for {}: {}", mintAddress, e.getMessage());
            return null;
        }
    }

    /**
     * 查询代币精度（decimals）
     */
    public int getDecimals(String mintAddress) {
        try {
            String body = """
                    {"jsonrpc":"2.0","id":1,"method":"getAccountInfo","params":["%s",{"encoding":"jsonParsed"}]}
                    """.formatted(mintAddress);
            JSONObject resp = postRpc(body);
            if (resp == null) return 9; // Solana 默认精度
            JSONObject result = resp.getJSONObject("result");
            if (result == null) return 9;
            JSONObject value = result.getJSONObject("value");
            if (value == null) return 9;
            JSONObject data = value.getJSONObject("data");
            if (data == null) return 9;
            JSONObject parsed = data.getJSONObject("parsed");
            if (parsed == null) return 9;
            JSONObject info = parsed.getJSONObject("info");
            return info != null ? info.getIntValue("decimals", 9) : 9;
        } catch (Exception e) {
            return 9;
        }
    }

    // ─── 内部方法 ──────────────────────────────────────────

    private JSONObject postRpc(String jsonBody) {
        String url = rpcUrl + "/?api-key=" + apiKey;
        Request request = new Request.Builder()
                .url(url)
                .post(RequestBody.create(jsonBody, JSON_MEDIA))
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                log.warn("[Helius] RPC error {}", response.code());
                return null;
            }
            ResponseBody rb = response.body();
            return rb != null ? JSON.parseObject(rb.string()) : null;
        } catch (Exception e) {
            log.warn("[Helius] RPC call failed: {}", e.getMessage());
            return null;
        }
    }
}

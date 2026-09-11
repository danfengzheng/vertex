package com.vertex.service.mm.userstream;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.vertex.service.mm.config.MmProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * 币安 listenKey 生命周期管理。
 * <p>
 * <ul>
 *   <li>POST /fapi/v1/listenKey —— 创建 listenKey，返回 60min TTL</li>
 *   <li>PUT  /fapi/v1/listenKey —— 30 分钟主动续期</li>
 *   <li>DELETE /fapi/v1/listenKey —— 关闭</li>
 * </ul>
 * 不依赖 order-service 的 client，独立轻量实现（此处只做私有流管理，不下单）。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ListenKeyManager {

    private static final String LISTEN_KEY_ENDPOINT = "/fapi/v1/listenKey";

    private final MmProperties properties;

    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build();

    /**
     * 创建 listenKey。
     * @param apiKey Binance API key（只需 futures 读权限）
     * @return listenKey 字符串
     */
    public String create(String apiKey) {
        Request req = new Request.Builder()
                .url(properties.getBinanceUsdcmBaseUrl() + LISTEN_KEY_ENDPOINT)
                .post(RequestBody.create("", MediaType.parse("application/x-www-form-urlencoded")))
                .addHeader("X-MBX-APIKEY", apiKey)
                .build();
        return exec(req, "create listenKey", true);
    }

    /**
     * 续期。返回 true = 续期成功。
     */
    public boolean keepalive(String apiKey) {
        Request req = new Request.Builder()
                .url(properties.getBinanceUsdcmBaseUrl() + LISTEN_KEY_ENDPOINT)
                .put(RequestBody.create("", MediaType.parse("application/x-www-form-urlencoded")))
                .addHeader("X-MBX-APIKEY", apiKey)
                .build();
        exec(req, "keepalive listenKey", false);
        return true;
    }

    /**
     * 关闭。
     */
    public void close(String apiKey) {
        Request req = new Request.Builder()
                .url(properties.getBinanceUsdcmBaseUrl() + LISTEN_KEY_ENDPOINT)
                .delete()
                .addHeader("X-MBX-APIKEY", apiKey)
                .build();
        exec(req, "close listenKey", false);
    }

    private String exec(Request req, String opName, boolean expectListenKey) {
        try (Response resp = http.newCall(req).execute()) {
            String body = resp.body() == null ? "" : resp.body().string();
            if (!resp.isSuccessful()) {
                log.warn("[ListenKeyMgr] {} failed: code={}, body={}", opName, resp.code(), body);
                throw new IOException("HTTP " + resp.code() + ": " + body);
            }
            if (expectListenKey) {
                JSONObject json = JSON.parseObject(body);
                String key = json.getString("listenKey");
                log.info("[ListenKeyMgr] {} ok: listenKey={}...{}",
                        opName, safeHead(key, 6), safeTail(key, 4));
                return key;
            }
            log.debug("[ListenKeyMgr] {} ok", opName);
            return null;
        } catch (IOException e) {
            log.error("[ListenKeyMgr] {} error: {}", opName, e.getMessage());
            throw new RuntimeException(e);
        }
    }

    private static String safeHead(String s, int n) {
        return s == null || s.length() <= n ? s : s.substring(0, n);
    }
    private static String safeTail(String s, int n) {
        return s == null || s.length() <= n ? s : s.substring(s.length() - n);
    }
}

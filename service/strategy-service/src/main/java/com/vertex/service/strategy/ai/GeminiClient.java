package com.vertex.service.strategy.ai;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.vertex.model.entity.ai.AiConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.concurrent.TimeUnit;

/**
 * Google Gemini API 客户端（同步调用）。
 * <p>
 * 只要 {@code vertex.ai.enabled=true}（yaml 安装开关）就会被注册；
 * 具体是否真的走 Gemini 由 {@link AiClientRouter} 根据 DB 里的 provider 字段决定。
 * </p>
 * <p>
 * 所有业务参数（api-key / model / base-url / timeout / max-retry）**运行时从
 * {@link AiConfigService#get()} 读**，UI 上改配置 5s 内生效，无需重启。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "vertex.ai", name = "enabled", havingValue = "true")
public class GeminiClient implements AiClient {

    private static final MediaType JSON_MEDIA = MediaType.parse("application/json; charset=utf-8");

    private final AiConfigService configService;
    /** 复用 quote-service 的 OkHttpClient bean */
    private final OkHttpClient quoteOkHttpClient;

    @Override public String providerName() { return "gemini"; }
    @Override public String currentModel() { return configService.get().getGeminiModel(); }

    /** 老单 prompt 入口，无 system message */
    @Override
    public JSONObject generateJson(String prompt, JSONObject responseSchema) throws AiException {
        return generateJson(null, prompt, responseSchema);
    }

    /** 双 prompt：systemPrompt 走 Gemini 的 systemInstruction 顶级字段 */
    @Override
    public JSONObject generateJson(String systemPrompt, String userPrompt, JSONObject responseSchema)
            throws AiException {
        AiConfig cfg = configService.get();
        if (!StringUtils.hasText(cfg.getGeminiApiKey())) {
            throw new AiException("Gemini api-key is not configured (ai_config.gemini_api_key)",
                    null, false);
        }
        int maxRetry = cfg.getGeminiMaxRetry() == null ? 2 : cfg.getGeminiMaxRetry();
        int maxAttempts = Math.max(1, maxRetry + 1);
        AiException lastError = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return doGenerate(cfg, systemPrompt, userPrompt, responseSchema);
            } catch (AiException e) {
                lastError = e;
                if (!e.isRetryable() || attempt == maxAttempts) {
                    throw e;
                }
                long backoff = 500L * (1L << (attempt - 1));
                log.warn("[GeminiClient] attempt {}/{} failed: {}, retry in {}ms",
                        attempt, maxAttempts, e.getMessage(), backoff);
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new AiException("Interrupted during retry", ie, false);
                }
            }
        }
        throw lastError != null ? lastError : new AiException("Unknown failure", null, false);
    }

    private JSONObject doGenerate(AiConfig cfg, String systemPrompt, String userPrompt,
                                  JSONObject responseSchema) throws AiException {
        String baseUrl = safe(cfg.getGeminiBaseUrl(), "https://generativelanguage.googleapis.com");
        String model = safe(cfg.getGeminiModel(), "gemini-2.0-flash");
        String url = String.format("%s/v1beta/models/%s:generateContent?key=%s",
                baseUrl, model, cfg.getGeminiApiKey());

        // 用当前配置的 timeout 单独建一个短命 client（不改全局）
        int timeout = cfg.getGeminiTimeoutSeconds() == null ? 30 : Math.max(5, cfg.getGeminiTimeoutSeconds());
        OkHttpClient httpClient = quoteOkHttpClient.newBuilder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(timeout, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .build();

        // 构造请求体
        JSONObject body = new JSONObject();

        // systemInstruction（若有）
        if (StringUtils.hasText(systemPrompt)) {
            JSONObject sysInstruction = new JSONObject();
            JSONArray sysParts = new JSONArray();
            JSONObject sysText = new JSONObject();
            sysText.put("text", systemPrompt);
            sysParts.add(sysText);
            sysInstruction.put("parts", sysParts);
            body.put("systemInstruction", sysInstruction);
        }

        // contents：业务 user prompt
        JSONArray contents = new JSONArray();
        JSONObject content = new JSONObject();
        JSONArray parts = new JSONArray();
        JSONObject textPart = new JSONObject();
        textPart.put("text", userPrompt);
        parts.add(textPart);
        content.put("parts", parts);
        contents.add(content);
        body.put("contents", contents);

        JSONObject genCfg = new JSONObject();
        genCfg.put("temperature", 0.2);
        genCfg.put("topP", 0.95);
        genCfg.put("maxOutputTokens", 1200);
        if (responseSchema != null) {
            genCfg.put("responseMimeType", "application/json");
            genCfg.put("responseSchema", responseSchema);
        }
        body.put("generationConfig", genCfg);

        Request req = new Request.Builder()
                .url(url)
                .post(RequestBody.create(body.toJSONString(), JSON_MEDIA))
                .build();

        try (Response resp = httpClient.newCall(req).execute()) {
            int code = resp.code();
            String respBody = resp.body() != null ? resp.body().string() : "";
            if (!resp.isSuccessful()) {
                boolean retryable = code >= 500 || code == 429;
                throw new AiException(
                        "Gemini HTTP " + code + ": " + truncate(respBody, 500),
                        null, retryable);
            }
            return parseResponse(respBody);
        } catch (AiException e) {
            throw e;
        } catch (Exception e) {
            throw new AiException("Network error: " + e.getMessage(), e, true);
        }
    }

    private JSONObject parseResponse(String body) throws AiException {
        try {
            JSONObject root = JSON.parseObject(body);
            JSONArray candidates = root.getJSONArray("candidates");
            if (candidates == null || candidates.isEmpty()) {
                String prompt_feedback = root.containsKey("promptFeedback")
                        ? root.getJSONObject("promptFeedback").toString()
                        : "<none>";
                throw new AiException("Gemini returned no candidates, feedback=" + prompt_feedback,
                        null, false);
            }
            JSONObject first = candidates.getJSONObject(0);
            String finishReason = first.getString("finishReason");
            JSONObject contentObj = first.getJSONObject("content");
            if (contentObj == null) {
                throw new AiException("Gemini candidate missing content, finishReason=" + finishReason,
                        null, false);
            }
            JSONArray parts = contentObj.getJSONArray("parts");
            if (parts == null || parts.isEmpty()) {
                throw new AiException("Gemini candidate parts empty", null, false);
            }
            String text = parts.getJSONObject(0).getString("text");
            if (!StringUtils.hasText(text)) {
                throw new AiException("Gemini returned empty text", null, false);
            }
            return JSON.parseObject(text);
        } catch (AiException e) {
            throw e;
        } catch (Exception e) {
            throw new AiException("Parse Gemini response failed: " + e.getMessage(), e, false);
        }
    }

    private static String safe(String s, String fallback) {
        return (s == null || s.isBlank()) ? fallback : s;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...(truncated)";
    }
}

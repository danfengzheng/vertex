package com.vertex.service.strategy.ai;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import jakarta.annotation.PostConstruct;
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
 * 仅在 {@code vertex.ai.gemini.enabled=true} 且
 * {@code vertex.ai.provider=gemini}（默认）时注册。
 * </p>
 * <p>
 * 用 OkHttp 直接打 HTTP，避免引入 Google SDK 依赖；使用 Gemini 原生
 * {@code generationConfig.responseSchema} 强制结构化 JSON 输出。
 * </p>
 */
/*
 * 激活条件：vertex.ai.gemini.enabled=true 且 vertex.ai.provider 为 gemini
 * （未配置 provider 时默认按 gemini 处理）。
 * 同一类不能重复 @ConditionalOnProperty，所以用单个 SpEL 表达式合并条件。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@org.springframework.boot.autoconfigure.condition.ConditionalOnExpression(
        "${vertex.ai.gemini.enabled:false} and '${vertex.ai.provider:gemini}'.equals('gemini')")
public class GeminiClient implements AiClient {

    private static final MediaType JSON_MEDIA = MediaType.parse("application/json; charset=utf-8");

    private final AiProperties aiProperties;
    /**
     * 复用 quote-service 的 OkHttpClient bean；strategy-service 同一容器内可见。
     * 这里基于配置的 timeout 再 newBuilder，避免硬改全局 client。
     */
    private final OkHttpClient quoteOkHttpClient;

    private OkHttpClient httpClient;

    @PostConstruct
    public void init() {
        AiProperties.Gemini cfg = aiProperties.getGemini();
        int timeout = Math.max(5, cfg.getTimeoutSeconds());
        this.httpClient = quoteOkHttpClient.newBuilder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(timeout, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .build();
        log.info("[GeminiClient] activated: model={}, baseUrl={}, timeoutSec={}",
                cfg.getModel(), cfg.getBaseUrl(), timeout);
        if (!StringUtils.hasText(cfg.getApiKey())) {
            log.warn("[GeminiClient] api-key is blank, all calls will fail");
        }
    }

    @Override public String providerName() { return "gemini"; }
    @Override public String currentModel() { return aiProperties.getGemini().getModel(); }

    /**
     * 调 Gemini 生成结构化 JSON。
     */
    @Override
    public JSONObject generateJson(String prompt, JSONObject responseSchema) throws AiException {
        AiProperties.Gemini cfg = aiProperties.getGemini();
        if (!StringUtils.hasText(cfg.getApiKey())) {
            throw new AiException("Gemini api-key is not configured (vertex.ai.gemini.api-key)",
                    null, false);
        }
        int maxAttempts = Math.max(1, cfg.getMaxRetry() + 1);
        AiException lastError = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return doGenerate(cfg, prompt, responseSchema);
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

    private JSONObject doGenerate(AiProperties.Gemini cfg, String prompt, JSONObject responseSchema)
            throws AiException {
        String url = String.format("%s/v1beta/models/%s:generateContent?key=%s",
                cfg.getBaseUrl(), cfg.getModel(), cfg.getApiKey());

        // 构造请求体
        JSONObject body = new JSONObject();
        JSONArray contents = new JSONArray();
        JSONObject content = new JSONObject();
        JSONArray parts = new JSONArray();
        JSONObject textPart = new JSONObject();
        textPart.put("text", prompt);
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

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...(truncated)";
    }
}

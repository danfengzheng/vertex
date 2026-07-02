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
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.concurrent.TimeUnit;

/**
 * DeepSeek API 客户端（OpenAI 兼容协议）。
 * <p>
 * 仅在 {@code vertex.ai.deepseek.enabled=true} 且
 * {@code vertex.ai.provider=deepseek} 时注册。
 * </p>
 * <p>
 * <b>协议差异</b>：DeepSeek 完全兼容 OpenAI Chat Completions：
 * <pre>
 * POST /v1/chat/completions
 * Authorization: Bearer &lt;api-key&gt;
 * {
 *   "model": "deepseek-chat",
 *   "messages": [{ "role": "user", "content": "..." }],
 *   "response_format": { "type": "json_object" },
 *   "temperature": 0.2,
 *   "max_tokens": 1200
 * }
 *
 * → { "choices": [ { "message": { "content": "&lt;json string&gt;" } } ] }
 * </pre>
 * </p>
 * <p>
 * <b>schema 适配</b>：DeepSeek/OpenAI 没有 Gemini 风格的 responseSchema，
 * 只支持 {@code response_format: { type: "json_object" }}。本实现把传入的 schema
 * 转成自然语言要求拼到 prompt 末尾，让模型按结构输出。
 * </p>
 */
/*
 * 激活条件：vertex.ai.deepseek.enabled=true 且 vertex.ai.provider=deepseek。
 * 同一类上不能重复 @ConditionalOnProperty（无 @Repeatable），统一改为单个 SpEL。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnExpression(
        "${vertex.ai.deepseek.enabled:false} and '${vertex.ai.provider:gemini}'.equals('deepseek')")
public class DeepSeekClient implements AiClient {

    private static final MediaType JSON_MEDIA = MediaType.parse("application/json; charset=utf-8");

    private final AiProperties aiProperties;
    private final OkHttpClient quoteOkHttpClient;

    private OkHttpClient httpClient;

    @PostConstruct
    public void init() {
        AiProperties.DeepSeek cfg = aiProperties.getDeepseek();
        int timeout = Math.max(5, cfg.getTimeoutSeconds());
        this.httpClient = quoteOkHttpClient.newBuilder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(timeout, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .build();
        log.info("[DeepSeekClient] activated: model={}, baseUrl={}, timeoutSec={}",
                cfg.getModel(), cfg.getBaseUrl(), timeout);
        if (!StringUtils.hasText(cfg.getApiKey())) {
            log.warn("[DeepSeekClient] api-key is blank, all calls will fail");
        }
    }

    @Override public String providerName() { return "deepseek"; }
    @Override public String currentModel() { return aiProperties.getDeepseek().getModel(); }

    @Override
    public JSONObject generateJson(String prompt, JSONObject responseSchema) throws AiException {
        // 老单 prompt 入口：无 system message，全部塞进 user role
        return generateJson(null, prompt, responseSchema);
    }

    @Override
    public JSONObject generateJson(String systemPrompt, String userPrompt, JSONObject responseSchema)
            throws AiException {
        AiProperties.DeepSeek cfg = aiProperties.getDeepseek();
        if (!StringUtils.hasText(cfg.getApiKey())) {
            throw new AiException("DeepSeek api-key is not configured (vertex.ai.deepseek.api-key)",
                    null, false);
        }
        int maxAttempts = Math.max(1, cfg.getMaxRetry() + 1);
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
                log.warn("[DeepSeekClient] attempt {}/{} failed: {}, retry in {}ms",
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

    private JSONObject doGenerate(AiProperties.DeepSeek cfg, String systemPrompt, String userPrompt,
                                  JSONObject responseSchema) throws AiException {
        String url = cfg.getBaseUrl().replaceAll("/+$", "") + "/v1/chat/completions";

        // schema 只拼在 user 里（system 已经明确了输出格式规则，避免重复干扰权重）
        String userWithSchema = appendSchemaToPrompt(userPrompt, responseSchema);

        // 构造 OpenAI 兼容请求体
        JSONObject body = new JSONObject();
        body.put("model", cfg.getModel());
        JSONArray messages = new JSONArray();
        // ── system 消息（若有）：语言硬约束 + 角色 —— OpenAI 协议下权重最高 ─
        if (StringUtils.hasText(systemPrompt)) {
            JSONObject sysMsg = new JSONObject();
            sysMsg.put("role", "system");
            sysMsg.put("content", systemPrompt);
            messages.add(sysMsg);
        }
        // ── user 消息：业务上下文 + schema ────────────────────────────
        JSONObject userMsg = new JSONObject();
        userMsg.put("role", "user");
        userMsg.put("content", userWithSchema);
        messages.add(userMsg);
        body.put("messages", messages);
        body.put("temperature", 0.2);
        body.put("max_tokens", 1200);
        // OpenAI 兼容的强制 JSON 输出（DeepSeek 支持）
        JSONObject respFmt = new JSONObject();
        respFmt.put("type", "json_object");
        body.put("response_format", respFmt);
        body.put("stream", false);

        Request req = new Request.Builder()
                .url(url)
                .post(RequestBody.create(body.toJSONString(), JSON_MEDIA))
                .addHeader("Authorization", "Bearer " + cfg.getApiKey())
                .build();

        try (Response resp = httpClient.newCall(req).execute()) {
            int code = resp.code();
            String respBody = resp.body() != null ? resp.body().string() : "";
            if (!resp.isSuccessful()) {
                boolean retryable = code >= 500 || code == 429;
                throw new AiException(
                        "DeepSeek HTTP " + code + ": " + truncate(respBody, 500),
                        null, retryable);
            }
            return parseResponse(respBody);
        } catch (AiException e) {
            throw e;
        } catch (Exception e) {
            throw new AiException("Network error: " + e.getMessage(), e, true);
        }
    }

    /**
     * 从 DeepSeek 响应中提取 choices[0].message.content，并把它解析为 JSONObject。
     */
    private JSONObject parseResponse(String body) throws AiException {
        try {
            JSONObject root = JSON.parseObject(body);
            JSONArray choices = root.getJSONArray("choices");
            if (choices == null || choices.isEmpty()) {
                throw new AiException("DeepSeek returned no choices", null, false);
            }
            JSONObject first = choices.getJSONObject(0);
            JSONObject message = first.getJSONObject("message");
            if (message == null) {
                throw new AiException("DeepSeek choice missing message", null, false);
            }
            String content = message.getString("content");
            if (!StringUtils.hasText(content)) {
                throw new AiException("DeepSeek returned empty content", null, false);
            }
            // response_format=json_object 时 content 是合法 JSON 字符串
            return JSON.parseObject(content.trim());
        } catch (AiException e) {
            throw e;
        } catch (Exception e) {
            throw new AiException("Parse DeepSeek response failed: " + e.getMessage(), e, false);
        }
    }

    /**
     * DeepSeek / OpenAI 不支持 Gemini 风格 responseSchema，但支持
     * response_format=json_object。这里把 schema 转成自然语言指令拼到 prompt 末尾，
     * 让模型主动按指定 key + 枚举值产出 JSON。
     */
    private String appendSchemaToPrompt(String prompt, JSONObject responseSchema) {
        if (responseSchema == null) return prompt;
        StringBuilder sb = new StringBuilder(prompt);
        sb.append("\n\n=== Output JSON Schema (strictly follow) ===\n");
        sb.append(responseSchema.toJSONString()).append('\n');
        sb.append("Respond with ONLY a single JSON object matching the schema above. ")
          .append("No markdown fences, no commentary outside the JSON.\n");
        return sb.toString();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...(truncated)";
    }
}

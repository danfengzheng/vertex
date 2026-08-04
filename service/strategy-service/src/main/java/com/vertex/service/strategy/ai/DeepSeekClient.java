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
 * DeepSeek API 客户端（OpenAI 兼容协议）。
 * <p>
 * 只要 {@code vertex.ai.enabled=true}（yaml 安装开关）就会被注册；具体是否被使用
 * 由 {@link AiClientRouter} 根据 DB 里的 provider 字段决定。
 * </p>
 * <p>
 * 所有业务参数（api-key / model / base-url / timeout / max-retry）从
 * {@link AiConfigService#get()} 读，UI 上改配置 5s 内生效，无需重启。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "vertex.ai", name = "enabled", havingValue = "true")
public class DeepSeekClient implements AiClient {

    private static final MediaType JSON_MEDIA = MediaType.parse("application/json; charset=utf-8");

    private final AiConfigService configService;
    private final OkHttpClient quoteOkHttpClient;

    @Override public String providerName() { return "deepseek"; }
    @Override public String currentModel() { return configService.get().getDeepseekModel(); }

    @Override
    public JSONObject generateJson(String prompt, JSONObject responseSchema) throws AiException {
        return generateJson(null, prompt, responseSchema);
    }

    @Override
    public JSONObject generateJson(String systemPrompt, String userPrompt, JSONObject responseSchema)
            throws AiException {
        AiConfig cfg = configService.get();
        if (!StringUtils.hasText(cfg.getDeepseekApiKey())) {
            throw new AiException("DeepSeek api-key is not configured (ai_config.deepseek_api_key)",
                    null, false);
        }
        int maxRetry = cfg.getDeepseekMaxRetry() == null ? 2 : cfg.getDeepseekMaxRetry();
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

    private JSONObject doGenerate(AiConfig cfg, String systemPrompt, String userPrompt,
                                  JSONObject responseSchema) throws AiException {
        String baseUrl = safe(cfg.getDeepseekBaseUrl(), "https://api.deepseek.com");
        String model = safe(cfg.getDeepseekModel(), "deepseek-chat");
        String url = baseUrl.replaceAll("/+$", "") + "/v1/chat/completions";

        int timeout = cfg.getDeepseekTimeoutSeconds() == null
                ? 60 : Math.max(5, cfg.getDeepseekTimeoutSeconds());
        OkHttpClient httpClient = quoteOkHttpClient.newBuilder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(timeout, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .build();

        // schema 拼进 user prompt（DeepSeek/OpenAI 不支持原生 responseSchema）
        String userWithSchema = appendSchemaToPrompt(userPrompt, responseSchema);

        JSONObject body = new JSONObject();
        body.put("model", model);
        JSONArray messages = new JSONArray();
        // system message（若有）：语言硬约束 + 角色，OpenAI 协议下权重最高
        if (StringUtils.hasText(systemPrompt)) {
            JSONObject sysMsg = new JSONObject();
            sysMsg.put("role", "system");
            sysMsg.put("content", systemPrompt);
            messages.add(sysMsg);
        }
        JSONObject userMsg = new JSONObject();
        userMsg.put("role", "user");
        userMsg.put("content", userWithSchema);
        messages.add(userMsg);
        body.put("messages", messages);
        body.put("temperature", 0.2);
        body.put("max_tokens", 1200);
        // OpenAI 兼容的强制 JSON 输出
        JSONObject respFmt = new JSONObject();
        respFmt.put("type", "json_object");
        body.put("response_format", respFmt);
        body.put("stream", false);

        // ── DeepSeek V4+ 思考模式控制（可选字段） ──────────────────
        // thinking={"type":"disabled"} 显式关闭思考 → 响应快 5-30 倍
        // thinking={"type":"enabled"}  显式开启思考 → 深度推理但慢
        // null 时不发这个字段，走模型默认
        Integer thinking = cfg.getDeepseekThinkingEnabled();
        if (thinking != null) {
            JSONObject thinkingCfg = new JSONObject();
            thinkingCfg.put("type", thinking == 1 ? "enabled" : "disabled");
            body.put("thinking", thinkingCfg);
        }
        // reasoning_effort: low / medium / high；仅 thinking=enabled 有意义
        String effort = cfg.getDeepseekReasoningEffort();
        if (effort != null && !effort.isBlank()
                && thinking != null && thinking == 1) {
            body.put("reasoning_effort", effort.toLowerCase());
        }

        Request req = new Request.Builder()
                .url(url)
                .post(RequestBody.create(body.toJSONString(), JSON_MEDIA))
                .addHeader("Authorization", "Bearer " + cfg.getDeepseekApiKey())
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
            return JSON.parseObject(content.trim());
        } catch (AiException e) {
            throw e;
        } catch (Exception e) {
            throw new AiException("Parse DeepSeek response failed: " + e.getMessage(), e, false);
        }
    }

    private String appendSchemaToPrompt(String prompt, JSONObject responseSchema) {
        if (responseSchema == null) return prompt;
        StringBuilder sb = new StringBuilder(prompt);
        sb.append("\n\n=== Output JSON Schema (strictly follow) ===\n");
        sb.append(responseSchema.toJSONString()).append('\n');
        sb.append("Respond with ONLY a single JSON object matching the schema above. ")
          .append("No markdown fences, no commentary outside the JSON.\n");
        return sb.toString();
    }

    private static String safe(String s, String fallback) {
        return (s == null || s.isBlank()) ? fallback : s;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...(truncated)";
    }
}

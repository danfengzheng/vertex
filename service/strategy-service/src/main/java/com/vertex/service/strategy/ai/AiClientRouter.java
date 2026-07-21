package com.vertex.service.strategy.ai;

import com.alibaba.fastjson2.JSONObject;
import com.vertex.model.entity.ai.AiConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * AI Provider 路由器 —— {@link AiClient} 的 {@code @Primary} 实现。
 * <p>
 * 所有 {@code @Autowired AiClient} 注入拿到的都是这个路由器；它根据
 * {@link AiConfigService} 里的 {@code provider} 字段（DB 里可热切换）
 * 委托到 {@link GeminiClient} 或 {@link DeepSeekClient}。
 * </p>
 * <p>
 * 用户在 UI「AI 配置」页把 provider 从 gemini 改成 deepseek 并保存 → 5s 内
 * （AiConfigService 缓存 TTL）下一次 AI 调用就走 DeepSeek，不需要重启服务。
 * </p>
 */
@Slf4j
@Component
@Primary
@ConditionalOnProperty(prefix = "vertex.ai", name = "enabled", havingValue = "true")
public class AiClientRouter implements AiClient {

    private final AiClient geminiClient;
    private final AiClient deepSeekClient;
    private final AiConfigService configService;

    public AiClientRouter(@Qualifier("geminiClient") AiClient geminiClient,
                          @Qualifier("deepSeekClient") AiClient deepSeekClient,
                          AiConfigService configService) {
        this.geminiClient = geminiClient;
        this.deepSeekClient = deepSeekClient;
        this.configService = configService;
    }

    /** 根据 DB 里的 provider 字段选实际 client */
    private AiClient current() {
        AiConfig cfg = configService.get();
        String p = cfg.getProvider();
        if ("deepseek".equalsIgnoreCase(p)) return deepSeekClient;
        // 默认 gemini（含 null / 空字符串 / 未知值 → 兜底到 gemini）
        return geminiClient;
    }

    @Override
    public String providerName() {
        return current().providerName();
    }

    @Override
    public String currentModel() {
        return current().currentModel();
    }

    @Override
    public JSONObject generateJson(String prompt, JSONObject responseSchema) throws AiException {
        return current().generateJson(prompt, responseSchema);
    }

    @Override
    public JSONObject generateJson(String systemPrompt, String userPrompt, JSONObject responseSchema)
            throws AiException {
        return current().generateJson(systemPrompt, userPrompt, responseSchema);
    }
}

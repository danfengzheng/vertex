package com.vertex.service.strategy.ai;

import com.vertex.model.entity.ai.AiConfig;
import com.vertex.service.strategy.mapper.AiConfigMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.concurrent.atomic.AtomicReference;

/**
 * AI 模块动态配置服务。
 * <p>
 * 从 {@code ai_config} 单行表读写；5s 内存缓存降低 DB 压力（AI 调用频繁读）。
 * DB 里没有配置行（首次部署未跑 V22）时返回内置默认（enabled=0 + 稳定的 base URL），
 * 让 AiClient 优雅降级；同时打 warn 日志提醒。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiConfigService {

    private static final long CACHE_TTL_MS = 5_000L;
    private static final Long CONFIG_ID = 1L;

    private final AiConfigMapper mapper;

    private final AtomicReference<CacheEntry> cache = new AtomicReference<>(null);

    @PostConstruct
    public void init() {
        AiConfig cfg = loadFromDb();
        log.info("[AiConfigService] initialized: enabled={}, provider={}, model={}, language={}",
                yes(cfg.getEnabled()), cfg.getProvider(), currentModel(cfg), cfg.getLanguage());
    }

    /** 拿当前配置（带 5s 缓存） */
    public AiConfig get() {
        CacheEntry e = cache.get();
        long now = System.currentTimeMillis();
        if (e != null && (now - e.loadedAt) < CACHE_TTL_MS) {
            return e.config;
        }
        AiConfig fresh = loadFromDb();
        cache.set(new CacheEntry(fresh, now));
        return fresh;
    }

    /** 保存并立即失效缓存 */
    public void save(AiConfig cfg, Long updateBy) {
        if (cfg.getId() == null) cfg.setId(CONFIG_ID);
        cfg.setUpdateBy(updateBy);
        int updated = mapper.updateById(cfg);
        if (updated == 0) {
            mapper.insert(cfg);
        }
        invalidate();
        log.info("[AiConfigService] config saved: enabled={}, provider={}, model={}, updateBy={}",
                yes(cfg.getEnabled()), cfg.getProvider(), currentModel(cfg), updateBy);
    }

    /** 显式失效缓存 */
    public void invalidate() {
        cache.set(null);
    }

    /** 便利：根据 provider 返回对应的 model */
    public String currentModel() {
        return currentModel(get());
    }

    /** 便利：根据 provider 返回对应的 api-key */
    public String currentApiKey() {
        AiConfig cfg = get();
        return "deepseek".equalsIgnoreCase(cfg.getProvider())
                ? cfg.getDeepseekApiKey()
                : cfg.getGeminiApiKey();
    }

    // ─── 内部 ─────────────────────────────────────────

    private AiConfig loadFromDb() {
        try {
            AiConfig row = mapper.selectById(CONFIG_ID);
            if (row != null) return row;
        } catch (Exception ex) {
            log.warn("[AiConfigService] load DB failed, using defaults: {}", ex.getMessage());
        }
        log.warn("[AiConfigService] no config row (id=1); run V22_ai_config.sql. Falling back to defaults (disabled).");
        return defaults();
    }

    private static AiConfig defaults() {
        return AiConfig.builder()
                .id(CONFIG_ID)
                .enabled(0)
                .provider("gemini")
                .language("zh-CN")
                .geminiApiKey(null)
                .geminiModel("gemini-2.0-flash")
                .geminiBaseUrl("https://generativelanguage.googleapis.com")
                .geminiTimeoutSeconds(30)
                .geminiMaxRetry(2)
                .deepseekApiKey(null)
                .deepseekModel("deepseek-chat")
                .deepseekBaseUrl("https://api.deepseek.com")
                .deepseekTimeoutSeconds(60)
                .deepseekMaxRetry(2)
                .build();
    }

    private static String currentModel(AiConfig cfg) {
        return "deepseek".equalsIgnoreCase(cfg.getProvider())
                ? cfg.getDeepseekModel()
                : cfg.getGeminiModel();
    }

    private static boolean yes(Integer v) {
        return v != null && v == 1;
    }

    private record CacheEntry(AiConfig config, long loadedAt) {}
}

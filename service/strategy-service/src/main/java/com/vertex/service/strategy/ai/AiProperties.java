package com.vertex.service.strategy.ai;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * AI 分析配置（支持多 provider：Gemini / DeepSeek）。
 * <pre>
 * vertex:
 *   ai:
 *     # 顶层 provider 选择（默认 gemini；改为 deepseek 切换）
 *     provider: gemini
 *     # 通用配置（所有 provider 共享）
 *     worker-threads: 2
 *     queue-capacity: 2000
 *     # Gemini 配置（provider=gemini 时生效）
 *     gemini:
 *       enabled: true
 *       api-key: ${GEMINI_API_KEY}
 *       model: gemini-2.0-flash
 *       base-url: https://generativelanguage.googleapis.com
 *       timeout-seconds: 30
 *       max-retry: 2
 *     # DeepSeek 配置（provider=deepseek 时生效）
 *     deepseek:
 *       enabled: true
 *       api-key: ${DEEPSEEK_API_KEY}
 *       model: deepseek-chat
 *       base-url: https://api.deepseek.com
 *       timeout-seconds: 60
 *       max-retry: 2
 * </pre>
 */
@Data
@Component
@ConfigurationProperties(prefix = "vertex.ai")
public class AiProperties {

    /**
     * Provider 选择：gemini / deepseek。
     * 默认 gemini。改后必须重启服务才能生效（Spring 配置绑定是启动期）。
     */
    private String provider = "gemini";

    /** 异步 worker 线程数；单策略 1 线程足够，多策略推荐 2–3 */
    private int workerThreads = 2;

    /** 异步任务队列容量；超出后新任务被拒绝（不阻塞主流程）*/
    private int queueCapacity = 2000;

    /**
     * AI 输出语言。
     * <p>
     * 影响 AI 在 prompt 中被要求用什么语言生成自由文本字段
     * （summary / keyFactors / risks / entryFactors / exitFactors / improvements）。
     * 枚举值（verdict / alignment / marketRegime / suggestedAction）始终保持英文原始 key，
     * 由前端 i18n 翻译显示。
     * </p>
     * <ul>
     *   <li>zh-CN / zh → 中文（简体）</li>
     *   <li>en / en-US → English</li>
     *   <li>其他 BCP-47 标签透传给模型（例如 ja、ko）</li>
     * </ul>
     */
    private String language = "zh-CN";

    private Gemini gemini = new Gemini();
    private DeepSeek deepseek = new DeepSeek();

    @Data
    public static class Gemini {
        /** 是否启用 Gemini */
        private boolean enabled = false;
        /** API Key（Google AI Studio 申请） */
        private String apiKey;
        /** 模型名 */
        private String model = "gemini-2.0-flash";
        /** API base URL；中国大陆可改为 Cloudflare Worker 反代 */
        private String baseUrl = "https://generativelanguage.googleapis.com";
        private int timeoutSeconds = 30;
        private int maxRetry = 2;
    }

    @Data
    public static class DeepSeek {
        /** 是否启用 DeepSeek */
        private boolean enabled = false;
        /** API Key（platform.deepseek.com 申请） */
        private String apiKey;
        /**
         * 模型名：
         * <ul>
         *   <li>deepseek-chat（V3 通用对话）</li>
         *   <li>deepseek-reasoner（R1 推理模型，强但慢）</li>
         * </ul>
         */
        private String model = "deepseek-chat";
        /** API base URL（OpenAI 兼容协议） */
        private String baseUrl = "https://api.deepseek.com";
        private int timeoutSeconds = 60;
        private int maxRetry = 2;
    }
}

package com.vertex.service.strategy.ai;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * AI 模块的**基础设施配置**（yaml，重启期读的）。
 * <p>
 * 与 {@link com.vertex.model.entity.ai.AiConfig}（DB 单行表）分工：
 * <ul>
 *   <li>本类：{@code enabled} bean 级安装开关 + {@code workerThreads} / {@code queueCapacity}
 *       线程池尺寸 —— 都是 bean 初始化时读的，改后必须重启</li>
 *   <li>AiConfig：provider / language / api-key / model / base-url / timeout / max-retry
 *       —— 可在 UI「AI 分析 → AI 配置」页热切换，5s 内所有 AI 调用生效</li>
 * </ul>
 * yaml 里的 {@code enabled=true} 之后，两个 AiClient（GeminiClient / DeepSeekClient）
 * 都会被注册；实际调用时由 {@link AiClientRouter} 根据 DB 里的 {@code provider} 决定
 * 用哪个。想彻底禁用 AI 也可以在 UI 上把 DB 的 {@code enabled} 关掉，无需重启。
 * </p>
 * <pre>
 * vertex:
 *   ai:
 *     enabled: true          # bean 级安装开关，重启生效
 *     worker-threads: 2
 *     queue-capacity: 2000
 * </pre>
 */
@Data
@Component
@ConfigurationProperties(prefix = "vertex.ai")
public class AiProperties {

    /**
     * bean 级安装开关。true 才会注册 AiClient 相关 bean。
     * 关掉 = 彻底禁用（UI 也无法打开）。生产建议永久 true，实际开关走 DB。
     */
    private boolean enabled = false;

    /** 异步 worker 线程数；单策略 1 线程足够，多策略推荐 2-3 */
    private int workerThreads = 2;

    /** 异步任务队列容量；超出后新任务被拒绝（不阻塞主流程）*/
    private int queueCapacity = 2000;
}

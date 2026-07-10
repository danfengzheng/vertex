package com.vertex.service.quote.scanner;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 币安现货成交量暴增扫描器的**基础设施配置**（yaml，重启生效）。
 * <p>
 * 与 {@link com.vertex.model.entity.quote.VolumeSurgeConfig} 分工：
 * <ul>
 *   <li>本类只放不能热改的部分：Binance API 地址、REST 并发数、权重软限、初始延迟</li>
 *   <li>VolumeSurgeConfig（DB 单行表）放业务参数：总开关 / 扫描间隔 / 各阈值 / 黑白名单 / Telegram 凭据</li>
 * </ul>
 * yaml 里的 {@code enabled} 是 <b>bean 级安装开关</b>（关掉 → 整套 scanner beans 都不注册，
 * 彻底禁用）；DB 里的 {@code enabled} 是<b>业务运行开关</b>（beans 存在，但 scan() 跳过工作），
 * UI 可热切换。
 * </p>
 * <pre>
 * vertex:
 *   quote:
 *     volume-surge:
 *       enabled: true               # 安装开关，重启生效
 *       api-url: https://api.binance.com
 *       max-concurrent-requests: 8
 *       weight-soft-limit: 4000
 *       soft-limit-pause-seconds: 15
 *       initial-delay-seconds: 60
 *       tick-interval-seconds: 60   # scanner 心跳，控制"检查 DB 配置"的频率
 * </pre>
 */
@Data
@Component
@ConfigurationProperties(prefix = "vertex.quote.volume-surge")
public class VolumeSurgeProperties {

    /**
     * bean 级安装开关。设 true 才会注册 VolumeSurgeScanner / VolumeSurgeConfigService 等 bean。
     * 关掉 = 彻底禁用（无法在 UI 打开）。
     * 生产建议：设 true 一次；实际开/关走 DB。
     */
    private boolean enabled = false;

    /** Binance REST 根地址 */
    private String apiUrl = "https://api.binance.com";

    /** 单次扫描并发 REST 请求上限 */
    private int maxConcurrentRequests = 8;

    /** X-MBX-USED-WEIGHT-1M 达到该值时主动暂停 */
    private int weightSoftLimit = 4000;

    /** 触发软限时暂停秒数 */
    private int softLimitPauseSeconds = 15;

    /** 启动后首次扫描的延迟（秒），让主服务预热完再扫 */
    private int initialDelaySeconds = 60;

    /**
     * Scanner 心跳周期（秒）。每次心跳查一次 DB 配置，
     * 达到 {@code scanIntervalMinutes} 后触发一次真正扫描。
     * 默认 60s = 1min，配置 5min 扫描时最坏延迟 1min，可接受。
     */
    private int tickIntervalSeconds = 60;
}

package com.vertex.service.farb.config;

import lombok.extern.slf4j.Slf4j;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/**
 * funding-arb-service 自动装配入口。
 * <p>
 * 仅当 {@code vertex.farb.enabled=true} 时激活。装载后：
 * <ul>
 *   <li>DataCollector 定时任务启动（拉 OI / LSR / Taker / Premium / Klines）</li>
 *   <li>SignalCache 内存态初始化（从 RocksDB 冷启动填充）</li>
 *   <li>RegimeScanner 定时任务启动</li>
 *   <li>ExitTriggerEngine 定时任务启动</li>
 * </ul>
 * 默认 false —— 老部署无感升级，用户主动启用。
 * </p>
 *
 * @see FarbProperties
 */
@Slf4j
@Configuration
@ConditionalOnProperty(prefix = "vertex.farb", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(FarbProperties.class)
@ComponentScan(basePackages = "com.vertex.service.farb")
@MapperScan(basePackages = {
        "com.vertex.service.farb.mapper",
        "com.vertex.service.farb.execution"    // FarbCredentialResolver 里嵌套 ExchangeAccountMapper
})
public class FarbAutoConfiguration {

    public FarbAutoConfiguration(FarbProperties properties) {
        log.info("[farb] enabled. usdmBase={} spotBase={} premiumPullSec={} oiLsrPullMin={}",
                properties.getBinanceUsdmBaseUrl(),
                properties.getBinanceSpotBaseUrl(),
                properties.getPremiumIndexPullSec(),
                properties.getOiLsrPullMin());
    }
}

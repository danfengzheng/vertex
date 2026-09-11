package com.vertex.service.mm.config;

import lombok.extern.slf4j.Slf4j;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/**
 * mm-service 自动装配入口。
 * <p>
 * 仅当 {@code vertex.mm.enabled=true} 时激活。装载后：
 * <ul>
 *   <li>私有 UserDataStream 建立</li>
 *   <li>各活跃策略的 BookTicker 订阅启动</li>
 *   <li>GridEngine 事件循环开始</li>
 * </ul>
 * 默认 false —— 老 Vertex 部署无感升级，用户主动启用。
 * </p>
 * <p>
 * <b>@MapperScan 说明</b>：VertexApplication 顶层的 {@code @MapperScan("com.vertex.service.*.mapper")}
 * 单层 * 不匹配 mm 的两层子包 {@code com.vertex.service.mm.grid.mapper}。此处独立扫描。
 * </p>
 */
@Slf4j
@Configuration
@ConditionalOnProperty(prefix = "vertex.mm", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(MmProperties.class)
@ComponentScan(basePackages = "com.vertex.service.mm")
@MapperScan(basePackages = {
        "com.vertex.service.mm.grid.mapper",
        "com.vertex.service.mm.grid.service"   // AccountCredentialResolver 里嵌套的 ExchangeAccountMapper
})
public class MmAutoConfiguration {

    public MmAutoConfiguration(MmProperties properties) {
        log.info("[mm-service] enabled. binanceUsdcmBaseUrl={}, keepaliveSec={}",
                properties.getBinanceUsdcmBaseUrl(), properties.getListenKeyKeepaliveSec());
    }
}

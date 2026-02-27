package com.vertex.service.chain.config;

import okhttp3.OkHttpClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.concurrent.TimeUnit;

/**
 * 链上分析模块自动配置
 * <p>
 * 仅在 vertex.chain.enabled=true（默认）时激活，
 * 通过 @ComponentScan 加载所有 @Component/@Service/@Repository 标注的 Bean。
 * @EnableScheduling 开启定时扫描任务。
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(ChainAnalysisProperties.class)
@ComponentScan(basePackages = "com.vertex.service.chain")
@ConditionalOnProperty(prefix = "vertex.chain", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ChainAnalysisAutoConfiguration {

    /**
     * 共享 OkHttpClient，用于所有链 REST API 调用。
     * 若容器中已有 OkHttpClient Bean 则复用。
     */
    @Bean("chainOkHttpClient")
    @ConditionalOnMissingBean(name = "chainOkHttpClient")
    public OkHttpClient chainOkHttpClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build();
    }
}

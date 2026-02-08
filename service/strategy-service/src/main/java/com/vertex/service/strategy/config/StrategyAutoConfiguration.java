package com.vertex.service.strategy.config;

import com.vertex.service.quote.store.RocksDBManager;
import com.vertex.service.strategy.store.RocksDBSignalStore;
import com.vertex.service.strategy.store.SignalStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * 策略模块自动配置
 */
@Configuration
@EnableAsync
@EnableConfigurationProperties(StrategyProperties.class)
@ComponentScan(basePackages = "com.vertex.service.strategy")
@ConditionalOnProperty(prefix = "vertex.strategy", name = "enabled", havingValue = "true", matchIfMissing = true)
public class StrategyAutoConfiguration {

    @Bean("strategyRocksDBManager")
    @ConditionalOnMissingBean(name = "strategyRocksDBManager")
    public RocksDBManager strategyRocksDBManager(StrategyProperties properties) {
        return new RocksDBManager(properties.getRocksdb().getDataDir());
    }

    @Bean
    @ConditionalOnMissingBean
    public SignalStore signalStore(@Qualifier("strategyRocksDBManager") RocksDBManager rocksDBManager) {
        return new RocksDBSignalStore(rocksDBManager);
    }
}

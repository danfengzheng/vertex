package com.vertex.service.quote.config;

import com.vertex.framework.socket.exchange.ExchangeConfig;
import com.vertex.framework.socket.exchange.ExchangeType;
import com.vertex.service.quote.converter.BinanceKLineConverter;
import com.vertex.service.quote.converter.KLineConverter;
import com.vertex.service.quote.converter.OkxKLineConverter;
import com.vertex.service.quote.notify.CompositeNotifier;
import com.vertex.service.quote.source.QuoteDataSource;
import com.vertex.service.quote.source.rest.BinanceRestClient;
import com.vertex.service.quote.source.rest.KLineRestClient;
import com.vertex.service.quote.source.rest.OkxRestClient;
import com.vertex.service.quote.source.ws.BinanceWsDataSource;
import com.vertex.service.quote.source.ws.OkxWsDataSource;
import com.vertex.service.quote.store.KLineStore;
import com.vertex.service.quote.store.RocksDBKLineStore;
import com.vertex.service.quote.store.RocksDBManager;
import okhttp3.OkHttpClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 行情模块自动配置
 * <p>
 * 根据 vertex.quote.* 配置自动装配各组件：
 * - RocksDB 存储层
 * - 交易所数据源（Binance、OKX）
 * - REST 客户端
 * - 转换器已通过 @Component 注册
 * - 通知器已通过 @Component + @ConditionalOnProperty 注册
 */
@Configuration
@EnableConfigurationProperties(QuoteProperties.class)
@ComponentScan(basePackages = "com.vertex.service.quote")
public class QuoteAutoConfiguration {

    // ==================== RocksDB 存储层 ====================

    @Bean
    @ConditionalOnMissingBean
    public RocksDBManager rocksDBManager(QuoteProperties properties) {
        return new RocksDBManager(properties.getRocksdb().getDataDir());
    }

    @Bean
    @ConditionalOnMissingBean
    public KLineStore klineStore(RocksDBManager rocksDBManager) {
        return new RocksDBKLineStore(rocksDBManager);
    }

    // ==================== HTTP 客户端 ====================

    @Bean
    @ConditionalOnMissingBean
    public OkHttpClient quoteOkHttpClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .build();
    }

    // ==================== 币安数据源 ====================

    @Bean
    @ConditionalOnProperty(prefix = "vertex.quote.exchange.binance", name = "enabled", havingValue = "true", matchIfMissing = true)
    public QuoteDataSource binanceWsDataSource(QuoteProperties properties,
                                               List<KLineConverter> converters,
                                               KLineStore klineStore,
                                               CompositeNotifier notifier) {
        KLineConverter converter = findConverter(converters, "binance");
        QuoteProperties.Exchange.ExchangeItem binanceConfig = properties.getExchange().getBinance();

        ExchangeConfig exchangeConfig = ExchangeConfig.builder()
                .exchangeType(ExchangeType.BINANCE)
                .wsUrl(binanceConfig.getWsUrl())
                .apiUrl(binanceConfig.getApiUrl())
                .apiKey(binanceConfig.getApiKey())
                .secretKey(binanceConfig.getSecretKey())
                .heartbeatIntervalSeconds(20)
                .autoReconnect(true)
                .build();

        return new BinanceWsDataSource(exchangeConfig, converter, klineStore, notifier);
    }

    @Bean
    @ConditionalOnProperty(prefix = "vertex.quote.exchange.binance", name = "enabled", havingValue = "true", matchIfMissing = true)
    public KLineRestClient binanceRestClient(OkHttpClient quoteOkHttpClient,
                                             List<KLineConverter> converters,
                                             QuoteProperties properties) {
        KLineConverter converter = findConverter(converters, "binance");
        return new BinanceRestClient(quoteOkHttpClient, converter,
                properties.getExchange().getBinance().getApiUrl());
    }

    // ==================== OKX 数据源 ====================

    @Bean
    @ConditionalOnProperty(prefix = "vertex.quote.exchange.okx", name = "enabled", havingValue = "true")
    public QuoteDataSource okxWsDataSource(QuoteProperties properties,
                                           List<KLineConverter> converters,
                                           KLineStore klineStore,
                                           CompositeNotifier notifier) {
        KLineConverter converter = findConverter(converters, "okx");
        QuoteProperties.Exchange.ExchangeItem okxConfig = properties.getExchange().getOkx();

        ExchangeConfig exchangeConfig = ExchangeConfig.builder()
                .exchangeType(ExchangeType.OKX)
                .wsUrl(okxConfig.getWsUrl())
                .apiUrl(okxConfig.getApiUrl())
                .apiKey(okxConfig.getApiKey())
                .secretKey(okxConfig.getSecretKey())
                .passphrase(okxConfig.getPassphrase())
                .heartbeatIntervalSeconds(25)
                .autoReconnect(true)
                .build();

        return new OkxWsDataSource(exchangeConfig, converter, klineStore, notifier);
    }

    @Bean
    @ConditionalOnProperty(prefix = "vertex.quote.exchange.okx", name = "enabled", havingValue = "true")
    public KLineRestClient okxRestClient(OkHttpClient quoteOkHttpClient,
                                         List<KLineConverter> converters,
                                         QuoteProperties properties) {
        KLineConverter converter = findConverter(converters, "okx");
        return new OkxRestClient(quoteOkHttpClient, converter,
                properties.getExchange().getOkx().getApiUrl());
    }

    // ==================== 辅助方法 ====================

    /**
     * 根据交易所标识查找对应的转换器
     */
    private KLineConverter findConverter(List<KLineConverter> converters, String exchangeCode) {
        return converters.stream()
                .filter(c -> exchangeCode.equals(c.exchangeCode()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "No KLineConverter found for exchange: " + exchangeCode));
    }
}

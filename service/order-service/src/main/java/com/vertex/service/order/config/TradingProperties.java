package com.vertex.service.order.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 交易模块配置
 */
@Data
@Component
@ConfigurationProperties(prefix = "vertex.trading")
public class TradingProperties {

    /** 是否启用交易模块 */
    private boolean enabled = false;

    /** 默认执行模式 */
    private String defaultExecutionMode = "PAPER";

    /** 币安配置 */
    private Binance binance = new Binance();

    @Data
    public static class Binance {
        /** API 地址 */
        private String apiUrl = "https://api.binance.com";
        /** 请求超时窗口（毫秒） */
        private int recvWindow = 5000;
    }
}

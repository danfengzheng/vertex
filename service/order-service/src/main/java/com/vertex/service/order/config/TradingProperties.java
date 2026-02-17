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

    /** Telegram 通知配置 */
    private Telegram telegram = new Telegram();

    @Data
    public static class Binance {
        /** API 地址 */
        private String apiUrl = "https://api.binance.com";
        /** 请求超时窗口（毫秒） */
        private int recvWindow = 5000;
    }

    @Data
    public static class Telegram {
        /** 是否启用 Telegram 通知 */
        private boolean enabled = false;
        /** Bot Token（从 @BotFather 获取） */
        private String botToken;
        /** Chat ID（用户或群组 ID） */
        private String chatId;
        /** API 地址（支持自定义代理） */
        private String apiUrl = "https://api.telegram.org";
    }
}

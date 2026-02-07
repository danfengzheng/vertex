package com.vertex.service.quote.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 行情模块配置属性
 * <p>
 * 配置前缀：vertex.quote
 *
 * <pre>
 * vertex:
 *   quote:
 *     rocksdb:
 *       data-dir: ./data/rocksdb/quote
 *     notify:
 *       event:
 *         enabled: true
 *       rocketmq:
 *         enabled: false
 *         topic: KLINE_UPDATE
 *     exchange:
 *       binance:
 *         enabled: true
 *         ws-url: wss://stream.binance.com:9443/ws
 *         api-url: https://api.binance.com
 *       okx:
 *         enabled: false
 *         ws-url: wss://ws.okx.com:8443/ws/v5/public
 *         api-url: https://www.okx.com
 * </pre>
 */
@Data
@ConfigurationProperties(prefix = "vertex.quote")
public class QuoteProperties {

    /** RocksDB 配置 */
    private RocksDB rocksdb = new RocksDB();

    /** 通知配置 */
    private Notify notify = new Notify();

    /** 交易所配置 */
    private Exchange exchange = new Exchange();

    // ==================== 内部类 ====================

    @Data
    public static class RocksDB {
        /** 数据存储目录 */
        private String dataDir = "./data/rocksdb/quote";
    }

    @Data
    public static class Notify {
        /** Spring Event 通知配置 */
        private Event event = new Event();
        /** RocketMQ 通知配置 */
        private RocketMQ rocketmq = new RocketMQ();

        @Data
        public static class Event {
            /** 是否启用，默认 true */
            private boolean enabled = true;
        }

        @Data
        public static class RocketMQ {
            /** 是否启用，默认 false */
            private boolean enabled = false;
            /** RocketMQ Topic */
            private String topic = "KLINE_UPDATE";
        }
    }

    @Data
    public static class Exchange {
        /** 币安配置 */
        private ExchangeItem binance = new ExchangeItem(
                true,
                "wss://stream.binance.com:9443/ws",
                "https://api.binance.com"
        );
        /** OKX 配置 */
        private ExchangeItem okx = new ExchangeItem(
                false,
                "wss://ws.okx.com:8443/ws/v5/public",
                "https://www.okx.com"
        );

        @Data
        public static class ExchangeItem {
            /** 是否启用 */
            private boolean enabled;
            /** WebSocket 地址 */
            private String wsUrl;
            /** REST API 地址 */
            private String apiUrl;
            /** API Key（可选） */
            private String apiKey;
            /** Secret Key（可选） */
            private String secretKey;
            /** Passphrase（OKX 等交易所需要） */
            private String passphrase;

            public ExchangeItem() {
            }

            public ExchangeItem(boolean enabled, String wsUrl, String apiUrl) {
                this.enabled = enabled;
                this.wsUrl = wsUrl;
                this.apiUrl = apiUrl;
            }
        }
    }
}

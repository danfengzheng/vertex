package com.vertex.framework.socket.exchange;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * 交易所连接配置
 */
@Data
@Builder
public class ExchangeConfig {

    /** 交易所类型 */
    private ExchangeType exchangeType;

    /** WebSocket 地址 */
    private String wsUrl;

    /** REST API 地址（用于签名等） */
    private String apiUrl;

    /** API Key */
    private String apiKey;

    /** Secret Key */
    private String secretKey;

    /** Passphrase（部分交易所需要，如 OKX） */
    private String passphrase;

    /** 心跳间隔（秒） */
    @Builder.Default
    private int heartbeatIntervalSeconds = 20;

    /** 是否启用自动重连 */
    @Builder.Default
    private boolean autoReconnect = true;

    /** 额外参数 */
    private Map<String, String> extras;
}

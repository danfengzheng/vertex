package com.vertex.service.mm.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * mm-service 配置根。
 * <p>
 * yaml 前缀 {@code vertex.mm}。总开关放这里，业务参数放 {@code grid_strategy} 表。
 * </p>
 */
@Data
@ConfigurationProperties(prefix = "vertex.mm")
public class MmProperties {

    /** 总开关。false 时所有 mm 组件不启动，等同不装模块。 */
    private boolean enabled = false;

    /** 币安 USDC-M 合约 base URL */
    private String binanceUsdcmBaseUrl = "https://fapi.binance.com";

    /** 币安 futures user data stream WS base */
    private String binanceUserStreamWsBase = "wss://fstream.binance.com/ws";

    /** 币安公有市场数据 WS base */
    private String binancePublicWsBase = "wss://fstream.binance.com/ws";

    /** listenKey 主动 keepalive 间隔（秒），必须 < 3600（币安 60min TTL） */
    private int listenKeyKeepaliveSec = 1800;

    /** WS 断连重试初始等待（秒） */
    private int wsReconnectInitialSec = 1;

    /** WS 断连重试最大等待（秒） */
    private int wsReconnectMaxSec = 30;

    /** 每次全量对账最短间隔（秒），防重连风暴 */
    private int reconcileMinIntervalSec = 60;

    /** BookTicker 缓存 TTL（毫秒），过期视为数据陈旧 */
    private long bookTickerCacheTtlMs = 5000L;
}

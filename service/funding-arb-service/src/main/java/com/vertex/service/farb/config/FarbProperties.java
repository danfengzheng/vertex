package com.vertex.service.farb.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * funding-arb-service 配置根。
 * <p>
 * yaml 前缀 {@code vertex.farb}。总开关放这里，策略业务参数放 {@code farb_strategy} 表。
 * </p>
 *
 * @see com.vertex.service.farb.config.FarbAutoConfiguration
 */
@Data
@ConfigurationProperties(prefix = "vertex.farb")
public class FarbProperties {

    /** 总开关。false 时所有 farb 组件不启动，等同不装模块。 */
    private boolean enabled = false;

    /** 币安 USDM 期货 base URL */
    private String binanceUsdmBaseUrl = "https://fapi.binance.com";

    /** 币安现货 base URL */
    private String binanceSpotBaseUrl = "https://api.binance.com";

    /** DataCollector：全市场 premiumIndex 拉取间隔（秒）*/
    private int premiumIndexPullSec = 30;

    /** DataCollector：OI/LSR/Taker 拉取间隔（分钟）*/
    private int oiLsrPullMin = 5;

    /** DataCollector：TakerBSR 拉取间隔（分钟）*/
    private int takerBsrPullMin = 15;

    /** DataCollector：K 线 vol 拉取间隔（分钟）*/
    private int volPullMin = 60;

    /** DataCollector：可用交集刷新间隔（分钟）*/
    private int universeRefreshMin = 60;

    /** DataCollector：单次并发拉取的 symbol 数（防止 rate limit 爆炸）*/
    private int concurrentPullers = 10;

    /** DataCollector：单批拉取的 REST 超时（毫秒）*/
    private int pullTimeoutMs = 5000;

    /** RocksDB 信号数据 TTL（天）；超过后 purge。0 = 不过期 */
    private int signalTtlDays = 180;

    /** 是否启用 DataCollector（可单独关闭做数据收集/回测分离）*/
    private boolean dataCollectorEnabled = true;
}

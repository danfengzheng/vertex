package com.vertex.service.quote.scanner;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 一次成交量暴增告警的完整上下文。
 * 用于 RocksDB 持久化 + Telegram 推送格式化。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VolumeSurgeAlert implements Serializable {

    private String exchange;          // 目前固定 "binance"
    private String symbol;            // 例如 "SUIUSDT"
    private String direction;         // UP / DOWN

    private long alertedAt;           // epoch ms
    private long triggerBarOpenTime;  // 触发这次判定的 1H K 线 openTime (epoch ms)

    // ─── 数值 ─────────────────────────────────────────────
    private double surgeRatio;              // current_1h / baseline_median
    private double current1hQuoteUsdt;      // 触发 K 线的 quoteVolume（约等于 USDT）
    private double baselineMedianUsdt;      // 过去 baselineHours 根 1H 的成交额中位数
    private double baselineP90Usdt;         // p90（用于观察对比，非判定核心）

    private double openPrice;
    private double closePrice;
    private double priceChange1hPct;        // (close - open) / open × 100

    private double vol24hUsdt;              // 24h ticker 上的 quoteVolume
    private double priceChange24hPct;

    /**
     * 触发时机：
     *   true  = 小时内实时触发（当前未收盘 K 线累计量已达标）
     *   false = 该 K 线收盘后触发（旧行为）
     */
    private boolean triggeredBeforeClose;

    /**
     * 触发时 K 线已经经过的分钟数（仅未收盘触发时有意义，0-60）。
     * 收盘触发时固定 60。
     */
    private int elapsedMinutes;
}

package com.vertex.service.strategy.engine;

import com.vertex.model.entity.quote.KLine;
import com.vertex.model.entity.quote.KLineInterval;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;

/**
 * 小周期 K 线 → 高周期「当前部分 bar」聚合工具。
 * <p>
 * 用于多时间框架策略中，以触发 bar 的 openTime 为锚点，
 * 将主周期（primary interval）的已收盘 K 线聚合成高周期（secondary interval）
 * 当前尚未收盘的「部分 bar」，追加到历史收盘 K 线列表末尾，供指标计算使用。
 * <p>
 * 解决的两个核心问题：
 * <ol>
 *   <li><b>回测 Lookahead Bias</b>：不直接使用 RocksDB 中存储的高周期完整 bar（含未来数据），
 *       而是仅用截至触发时刻已收盘的低周期 K 线重建当前状态。</li>
 *   <li><b>实盘/回测一致</b>：两者都通过相同的聚合逻辑构建当前周期 bar，
 *       消除信号漂移，实现完全对齐。</li>
 *   <li><b>纳入当前周期成交量</b>：高周期成交量/成交额指标反映当前周期截至触发时刻的实际积累量，
 *       而非上一根已收盘高周期 bar 的历史量。</li>
 * </ol>
 * <p>
 * 使用示例（1m 策略 + 15m 指标）：
 * <pre>
 *   触发时刻：14:07（1m bar 刚收盘）
 *   已有 15m closed bars：[..., 13:45–14:00]
 *   聚合 1m[14:00..14:06] → partial 15m bar（closed=false）
 *   传入指标：[..., 13:45–14:00, 14:00–14:06(partial)]
 * </pre>
 */
public class KLinePartialBarBuilder {

    private KLinePartialBarBuilder() {}

    /**
     * 从主周期 K 线中提取属于高周期当前 bar 的部分，聚合成 partial bar。
     *
     * @param primaryKlines   主周期 K 线列表（时间升序，仅已收盘）
     * @param targetInterval  目标高周期（secondary interval，必须 > primaryKlines 的周期）
     * @param triggerOpenTime 触发 bar 的 openTime（毫秒），作为聚合窗口右端（含）
     * @param exchange        交易所标识
     * @param symbol          交易对
     * @return partial bar（closed=false）；若当前高周期内无主周期数据则返回 null
     */
    public static KLine buildPartialBar(List<KLine> primaryKlines,
                                        KLineInterval targetInterval,
                                        long triggerOpenTime,
                                        String exchange,
                                        String symbol) {
        long intervalMillis = targetInterval.getMillis();
        // 当前高周期的起始时间（向下取整到周期边界）
        long periodStart = (triggerOpenTime / intervalMillis) * intervalMillis;

        // 筛选属于当前高周期且 openTime <= triggerOpenTime 的主周期已收盘 bars。
        // 调用方保证 primaryKlines 已按 openTime 升序排列，无需再次排序。
        List<KLine> periodBars = primaryKlines.stream()
                .filter(k -> k.getOpenTime() >= periodStart && k.getOpenTime() <= triggerOpenTime)
                .toList();

        if (periodBars.isEmpty()) {
            return null;
        }

        BigDecimal open = periodBars.get(0).getOpen();
        BigDecimal close = periodBars.get(periodBars.size() - 1).getClose();
        // close 可能在 WS 重连时为 null（bar 未完整推送）；fallback 优先用 close，其次用 open，
        // 确保 high/low 不为 null，避免下游指标（ADX 等）NPE。
        BigDecimal hlFallback = close != null ? close : open;
        BigDecimal high = periodBars.stream()
                .map(KLine::getHigh).filter(v -> v != null)
                .max(Comparator.naturalOrder()).orElse(hlFallback);
        BigDecimal low = periodBars.stream()
                .map(KLine::getLow).filter(v -> v != null)
                .min(Comparator.naturalOrder()).orElse(hlFallback);
        BigDecimal volume = periodBars.stream()
                .map(KLine::getVolume).filter(v -> v != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal quoteVolume = periodBars.stream()
                .map(KLine::getQuoteVolume).filter(v -> v != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        int trades = periodBars.stream()
                .mapToInt(k -> k.getTrades() != null ? k.getTrades() : 0)
                .sum();

        return KLine.builder()
                .exchange(exchange)
                .symbol(symbol)
                .interval(targetInterval)
                .openTime(periodStart)
                .closeTime(periodStart + intervalMillis - 1)
                .open(open)
                .high(high)
                .low(low)
                .close(close)
                .volume(volume)
                .quoteVolume(quoteVolume)
                .trades(trades)
                .closed(false)
                .build();
    }
}

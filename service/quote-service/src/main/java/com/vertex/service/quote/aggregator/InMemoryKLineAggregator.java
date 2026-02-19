package com.vertex.service.quote.aggregator;

import com.vertex.model.entity.quote.KLine;
import com.vertex.model.entity.quote.KLineInterval;
import com.vertex.service.quote.notify.CompositeNotifier;
import com.vertex.service.quote.store.KLineStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存 K 线聚合器：仅处理日 K 以下周期（M1～H12）。
 * <p>
 * 接收逐笔成交，按时间桶更新当前根；周期结束时落盘并通知。
 * 落盘时通过 {@link KLineStore#save} 写入存储（当前实现为 RocksDB），与 WS 直连 K 线同一存储。
 * 读路径：先查本内存（当前根），再查 RocksDB / REST。
 */
@Slf4j
@Component
public class InMemoryKLineAggregator {

    private static final long INTRADAY_THRESHOLD_MS = 86_400_000L; // 1d

    private final KLineStore klineStore;
    private final CompositeNotifier notifier;

    /** (exchange:symbol:interval) -> 当前根（未收盘） */
    private final Map<String, CurrentBar> currentBars = new ConcurrentHashMap<>();

    public InMemoryKLineAggregator(KLineStore klineStore, CompositeNotifier notifier) {
        this.klineStore = klineStore;
        this.notifier = notifier;
    }

    /**
     * 是否仅处理日 K 以下
     */
    public static boolean isIntraday(KLineInterval interval) {
        return interval.getMillis() < INTRADAY_THRESHOLD_MS;
    }

    /**
     * 接收逐笔成交，更新所有日 K 以下周期的当前根
     */
    public void onTrade(String exchange, String symbol, BigDecimal price, BigDecimal quantity, long timeMs) {
        for (KLineInterval interval : KLineInterval.values()) {
            if (!isIntraday(interval)) {
                continue;
            }
            long openTime = (timeMs / interval.getMillis()) * interval.getMillis();
            long closeTime = openTime + interval.getMillis() - 1;

            String key = key(exchange, symbol, interval);
            currentBars.compute(key, (k, existing) -> {
                if (existing != null && existing.openTime == openTime) {
                    existing.update(price, quantity);
                    return existing;
                }
                if (existing != null) {
                    flushBar(exchange, symbol, interval, existing);
                }
                return new CurrentBar(openTime, closeTime, price, quantity);
            });
        }
    }

    /**
     * 获取内存中某周期的最新一根（当前根，可能未收盘）
     */
    public KLine getLatest(String exchange, String symbol, KLineInterval interval) {
        if (!isIntraday(interval)) {
            return null;
        }
        CurrentBar bar = currentBars.get(key(exchange, symbol, interval));
        if (bar == null) {
            return null;
        }
        return bar.toKLine(exchange, symbol, interval, false);
    }

    /**
     * 获取内存中某周期近期已关闭的根（可选实现：暂不保留多根，仅当前根）
     * 若需「近期 N 根」可在此扩展。
     */
    public List<KLine> getRecentClosed(String exchange, String symbol, KLineInterval interval, int maxCount) {
        return Collections.emptyList();
    }

    /** 周期结束：持久化到 RocksDB（klineStore）并发事件 */
    private void flushBar(String exchange, String symbol, KLineInterval interval, CurrentBar bar) {
        KLine kline = bar.toKLine(exchange, symbol, interval, true);
        klineStore.save(kline);  // 写入 RocksDB，与 ≥1D 的 kline WS 同库
        notifier.notifyKLine(kline);
        log.debug("[Aggregator] Flushed {} {} {} openTime={}", exchange, symbol, interval.getCode(), bar.openTime);
    }

    private static String key(String exchange, String symbol, KLineInterval interval) {
        return exchange + ":" + symbol + ":" + interval.getCode();
    }

    private static final class CurrentBar {
        private final long openTime;
        private final long closeTime;
        private BigDecimal open;
        private BigDecimal high;
        private BigDecimal low;
        private BigDecimal close;
        private BigDecimal volume;

        CurrentBar(long openTime, long closeTime, BigDecimal price, BigDecimal quantity) {
            this.openTime = openTime;
            this.closeTime = closeTime;
            this.open = price;
            this.high = price;
            this.low = price;
            this.close = price;
            this.volume = quantity != null ? quantity : BigDecimal.ZERO;
        }

        void update(BigDecimal price, BigDecimal quantity) {
            if (price != null) {
                if (high == null || price.compareTo(high) > 0) high = price;
                if (low == null || price.compareTo(low) < 0) low = price;
                close = price;
            }
            if (quantity != null) {
                volume = volume.add(quantity);
            }
        }

        KLine toKLine(String exchange, String symbol, KLineInterval interval, boolean closed) {
            return KLine.builder()
                    .exchange(exchange)
                    .symbol(symbol)
                    .interval(interval)
                    .openTime(openTime)
                    .closeTime(closeTime)
                    .open(open)
                    .high(high)
                    .low(low)
                    .close(close)
                    .volume(volume != null ? volume : BigDecimal.ZERO)
                    .quoteVolume(null)
                    .trades(null)
                    .closed(closed)
                    .build();
        }
    }
}

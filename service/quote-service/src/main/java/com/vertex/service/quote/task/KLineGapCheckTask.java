package com.vertex.service.quote.task;

import com.vertex.model.entity.quote.KLine;
import com.vertex.model.entity.quote.KLineInterval;
import com.vertex.service.quote.config.QuoteProperties;
import com.vertex.service.quote.service.QuoteBackfillService;
import com.vertex.service.quote.source.QuoteDataSource;
import com.vertex.service.quote.store.KLineStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * K 线缺口定时检查
 * <p>
 * 每隔一定时间（默认 30 分钟）检查当前所有订阅的 (exchange, symbol, interval) 在最近 N 天（默认 7 天）
 * 内的 K 线是否连续，发现缺口则打日志；若开启自动回填则调用 REST 补全缺口。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "vertex.quote.kline-check", name = "enabled", havingValue = "true", matchIfMissing = true)
public class KLineGapCheckTask {

    private static final int MAX_BARS_QUERY = 50_000;

    private final QuoteProperties properties;
    private final List<QuoteDataSource> dataSources;
    private final KLineStore klineStore;
    private final QuoteBackfillService backfillService;

    @Scheduled(fixedDelayString = "${vertex.quote.kline-check.interval-ms:1800000}")
    public void checkRecentKlineGaps() {
        QuoteProperties.KlineCheck config = properties.getKlineCheck();
        if (!config.isEnabled()) {
            return;
        }
        long now = System.currentTimeMillis();
        long lookbackMs = (long) config.getLookbackDays() * 24 * 60 * 60 * 1000;
        long startTime = now - lookbackMs;

        for (QuoteDataSource ds : dataSources) {
            String exchange = ds.exchangeCode();
            List<Map<String, String>> subs = ds.getSubscriptions();
            if (subs == null || subs.isEmpty()) {
                continue;
            }
            for (Map<String, String> sub : subs) {
                String symbol = sub.get("symbol");
                String intervalStr = sub.get("interval");
                if (symbol == null || intervalStr == null) {
                    continue;
                }
                KLineInterval interval;
                try {
                    interval = KLineInterval.valueOf(intervalStr);
                } catch (IllegalArgumentException e) {
                    log.debug("[KlineCheck] Skip unknown interval '{}' for {}:{}", intervalStr, exchange, symbol);
                    continue;
                }
                try {
                    checkOne(exchange, symbol, interval, startTime, now, config.isAutoBackfill());
                } catch (Exception e) {
                    log.warn("[KlineCheck] Failed to check {}:{}:{}: {}", exchange, symbol, interval.getCode(), e.getMessage());
                }
            }
        }
    }

    private void checkOne(String exchange, String symbol, KLineInterval interval,
                         long startTime, long endTime, boolean autoBackfill) {
        List<KLine> list = klineStore.query(exchange, symbol, interval, startTime, endTime, MAX_BARS_QUERY, true);
        long step = interval.getMillis();
        Set<Long> actualOpenTimes = list.stream().map(KLine::getOpenTime).collect(Collectors.toSet());

        long expectedStart = (startTime / step) * step;
        if (expectedStart < startTime) {
            expectedStart += step;
        }
        List<long[]> gaps = new ArrayList<>();
        long t = expectedStart;
        long gapStart = -1;
        while (t <= endTime) {
            if (!actualOpenTimes.contains(t)) {
                if (gapStart < 0) {
                    gapStart = t;
                }
            } else {
                if (gapStart >= 0) {
                    gaps.add(new long[]{gapStart, t - step});
                    gapStart = -1;
                }
            }
            t += step;
        }
        if (gapStart >= 0) {
            long lastExpected = (endTime / step) * step;
            gaps.add(new long[]{gapStart, lastExpected});
        }

        if (gaps.isEmpty()) {
            log.debug("[KlineCheck] {} {} {} last {} days: no gaps", exchange, symbol, interval.getCode(), properties.getKlineCheck().getLookbackDays());
            return;
        }

        int missingBars = 0;
        for (long[] g : gaps) {
            missingBars += (int) ((g[1] - g[0]) / step) + 1;
        }
        log.info("[KlineCheck] {} {} {} last {} days: {} gap(s), {} missing bar(s)",
                exchange, symbol, interval.getCode(), properties.getKlineCheck().getLookbackDays(), gaps.size(), missingBars);

        if (autoBackfill) {
            int filled = 0;
            for (long[] g : gaps) {
                try {
                    filled += backfillService.fillRange(exchange, symbol, interval, g[0], g[1]);
                } catch (Exception e) {
                    log.warn("[KlineCheck] Backfill failed for {} {} {} [{}, {}]: {}",
                            exchange, symbol, interval.getCode(), g[0], g[1], e.getMessage());
                }
            }
            if (filled > 0) {
                log.info("[KlineCheck] {} {} {} auto-backfill inserted {} bars", exchange, symbol, interval.getCode(), filled);
            }
        }
    }
}

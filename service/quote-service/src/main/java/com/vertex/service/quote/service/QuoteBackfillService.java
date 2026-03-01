package com.vertex.service.quote.service;

import com.vertex.api.quote.IKLineService;
import com.vertex.model.entity.quote.KLine;
import com.vertex.model.entity.quote.KLineInterval;
import com.vertex.service.quote.source.rest.KLineRestClient;
import com.vertex.service.quote.store.KLineStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * K 线缺口回填服务
 * <p>
 * 对指定 (exchange, symbol, interval) 在 [startTime, endTime] 范围内从交易所 REST 拉取并仅写入缺失 bar。
 * 供定时缺口检查任务、手动 backfill 接口与策略预热复用。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QuoteBackfillService {

    private final List<KLineRestClient> restClients;
    private final IKLineService klineService;
    private final KLineStore klineStore;

    private static final int BATCH_LIMIT_BINANCE = 1000;
    private static final int BATCH_LIMIT_OKX = 300;

    /**
     * 补全区间内所有缺口（含首、尾、中间），先查 Store 再按缺口段逐段 REST 回填。
     *
     * @return 实际写入的条数
     */
    public int fillGapsInRange(String exchange, String symbol, KLineInterval interval,
                               long startTime, long endTime) {
        List<KLine> existing = klineStore.query(
                exchange, symbol, interval,
                startTime, endTime, Integer.MAX_VALUE, true);

        if (existing.isEmpty()) {
            return fillRange(exchange, symbol, interval, startTime, endTime);
        }

        long step = interval.getMillis();
        List<long[]> ranges = new ArrayList<>();

        long firstOpen = existing.get(0).getOpenTime();
        if (firstOpen > startTime) {
            ranges.add(new long[]{startTime, firstOpen - 1});
        }

        for (int i = 1; i < existing.size(); i++) {
            long prevOpen = existing.get(i - 1).getOpenTime();
            long nextOpen = existing.get(i).getOpenTime();
            long gapStart = prevOpen + step;
            if (gapStart < nextOpen) {
                long gapEnd = nextOpen - step;
                ranges.add(new long[]{gapStart, gapEnd});
            }
        }

        long lastClose = existing.get(existing.size() - 1).getCloseTime();
        if (lastClose < endTime) {
            ranges.add(new long[]{lastClose + 1, endTime});
        }

        // 已存在但未收的 K 线也纳入重填：重新拉取后按时间重设 closed，避免补充数据一直显示未收
        for (KLine k : existing) {
            if (!Boolean.TRUE.equals(k.getClosed())) {
                long open = k.getOpenTime();
                ranges.add(new long[]{open, open + step - 1});
            }
        }

        int total = 0;
        for (long[] r : ranges) {
            total += fillRange(exchange, symbol, interval, r[0], r[1]);
        }
        if (total > 0) {
            log.info("[Backfill] fillGapsInRange {} {} {} [{}, {}]: {} bars written (gaps + unclosed refreshed)",
                    exchange, symbol, interval.getCode(), startTime, endTime, total);
        }
        return total;
    }

    /**
     * 回填指定区间的缺口（仅写入 Store 中不存在的 K 线）
     *
     * @return 实际写入的条数
     */
    public int fillRange(String exchange, String symbol, KLineInterval interval,
                         long startTime, long endTime) {
        KLineRestClient client = restClients.stream()
                .filter(c -> exchange.equalsIgnoreCase(c.exchangeCode()))
                .findFirst()
                .orElse(null);
        if (client == null) {
            log.warn("[Backfill] No REST client for exchange '{}', skip fill range", exchange);
            return 0;
        }
        int batchLimit = "okx".equalsIgnoreCase(exchange) ? BATCH_LIMIT_OKX : BATCH_LIMIT_BINANCE;
        long intervalMillis = interval.getMillis();
        long windowMillis = intervalMillis * batchLimit;
        long cursor = startTime;
        int totalCount = 0;
        while (cursor <= endTime) {
            long windowEnd = Math.min(cursor + windowMillis - 1, endTime);
            List<KLine> batch = client.fetchKLines(symbol, interval, cursor, windowEnd, batchLimit);
            if (!batch.isEmpty()) {
                totalCount += klineService.saveBatchFillingGapsOnly(batch);
                long lastClose = batch.get(batch.size() - 1).getCloseTime();
                cursor = lastClose + 1;
            } else {
                cursor = windowEnd + 1;
            }
        }
        return totalCount;
    }
}

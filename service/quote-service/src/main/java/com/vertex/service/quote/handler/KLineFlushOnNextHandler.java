package com.vertex.service.quote.handler;

import com.vertex.model.entity.quote.KLine;
import com.vertex.model.entity.quote.KLineInterval;
import com.vertex.service.quote.notify.CompositeNotifier;
import com.vertex.service.quote.store.KLineStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * K 线 WS 入库辅助：当收到「下一根」的 openTime 时，将上一根以 closed=true 入库并通知。
 * <p>
 * 用于兼容没有 closed 标识的交易所：不依赖交易所推送 closed=true，
 * 只要出现下一时间点的数据即认为上一根已收盘。
 * </p>
 * <p>
 * 通知规则（保证每根 bar 只通知一次、且不遗漏）：
 * 1）当前条：若交易所带 closed=true，则入库后立即通知（避免因下一根未到而永远漏发）；
 * 2）flush 上一根时：仅当上一根此前未带 closed 时才通知（由本逻辑设为 closed 时通知），避免与 1）重复。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KLineFlushOnNextHandler {

    private final KLineStore klineStore;
    private final CompositeNotifier notifier;

    /** key: exchange:symbol:interval -> 上一根 KLine（用于在见到下一 openTime 时以 closed 入库） */
    private final ConcurrentHashMap<String, KLine> lastByKey = new ConcurrentHashMap<>();

    /**
     * 提交一条 K 线：若 openTime 与上一根不同，先将上一根以 closed=true 入库，仅当其此前未 closed 时通知；当前条入库，若带 closed=true 则通知。
     *
     * @param kline 当前收到的 K 线（可为交易所的 closed 或未 closed）
     */
    public void submit(KLine kline) {
        if (kline == null) {
            return;
        }
        String key = key(kline.getExchange(), kline.getSymbol(), kline.getInterval());
        KLine previous = lastByKey.put(key, kline);

        if (previous != null && !previous.getOpenTime().equals(kline.getOpenTime())) {
            boolean wasAlreadyClosed = Boolean.TRUE.equals(previous.getClosed());
            previous.setClosed(true);
            // 加密货币 close[n] == open[n+1]，若 previous bar 因 WS 重连等原因未收到收盘数据
            // （close/high/low 为 null），用下一根的 open 补全，确保存入 KLineStore 的收盘 bar 字段完整。
            if (previous.getClose() == null && kline.getOpen() != null) {
                previous.setClose(kline.getOpen());
                log.warn("[KLineFlushOnNext] close was null for {} {} {} openTime={}, filled with next open={}",
                        previous.getExchange(), previous.getSymbol(),
                        previous.getInterval().getCode(), previous.getOpenTime(), kline.getOpen());
            }
            if (previous.getHigh() == null && previous.getClose() != null) {
                // high 缺失时取 open/close 中的较大值，保持 OHLC 逻辑一致
                previous.setHigh(previous.getOpen() != null
                        ? previous.getOpen().max(previous.getClose())
                        : previous.getClose());
            }
            if (previous.getLow() == null && previous.getClose() != null) {
                // low 缺失时取 open/close 中的较小值
                previous.setLow(previous.getOpen() != null
                        ? previous.getOpen().min(previous.getClose())
                        : previous.getClose());
            }
            klineStore.save(previous);
            if (!wasAlreadyClosed) {
                notifier.notifyKLine(previous);
                log.debug("[KLineFlushOnNext] Flushed previous bar as closed: {} {} {} openTime={}",
                        kline.getExchange(), kline.getSymbol(), kline.getInterval().getCode(), previous.getOpenTime());
            }
        }

        klineStore.save(kline);
        if (Boolean.TRUE.equals(kline.getClosed())) {
            notifier.notifyKLine(kline);
        }
    }

    private static String key(String exchange, String symbol, KLineInterval interval) {
        return exchange + ":" + symbol + ":" + interval.getCode();
    }
}

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
 * 仅在被 flush 的「上一根」时发事件，当前条只入库不通知，避免同一根 K 线因多次 WS 更新触发多次策略、产生重复信号。
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
     * 提交一条 K 线：若 openTime 与上一根不同，先将上一根以 closed=true 入库并通知；当前条只入库，不通知（等下一根到来时再以 closed 通知）。
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
            previous.setClosed(true);
            klineStore.save(previous);
            notifier.notifyKLine(previous);
            log.debug("[KLineFlushOnNext] Flushed previous bar as closed: {} {} {} openTime={}",
                    kline.getExchange(), kline.getSymbol(), kline.getInterval().getCode(), previous.getOpenTime());
        }

        klineStore.save(kline);
        // 当前条只入库，不通知：避免同一根 K 线因 WS 多次推送触发多次策略、产生重复信号；该根会在「下一根」到来时以 closed 被 flush 并通知
    }

    private static String key(String exchange, String symbol, KLineInterval interval) {
        return exchange + ":" + symbol + ":" + interval.getCode();
    }
}

package com.vertex.service.mm.market;

import com.vertex.service.mm.config.MmProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 10 档深度快照缓存。SmartPricePlacer + PaperFillSimulator 从这里读。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderBookCache {

    private final MmProperties properties;
    private final ConcurrentMap<String, OrderBookSnapshot> snapshots = new ConcurrentHashMap<>();

    public void update(OrderBookSnapshot snap) {
        if (snap == null || snap.getSymbol() == null) return;
        snapshots.put(snap.getSymbol().toUpperCase(), snap);
    }

    public OrderBookSnapshot get(String symbol) {
        if (symbol == null) return null;
        OrderBookSnapshot s = snapshots.get(symbol.toUpperCase());
        if (s == null) return null;
        long age = System.currentTimeMillis() - s.getUpdateTimeMs();
        if (age > properties.getBookTickerCacheTtlMs()) {
            log.debug("[OrderBookCache] symbol={} stale (age={}ms)", symbol, age);
            return null;
        }
        return s;
    }

    public OrderBookSnapshot getRaw(String symbol) {
        return snapshots.get(symbol.toUpperCase());
    }
}

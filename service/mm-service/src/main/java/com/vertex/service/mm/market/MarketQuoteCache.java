package com.vertex.service.mm.market;

import com.vertex.service.mm.config.MmProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 内存中的 bid/ask 快照缓存。
 * <p>
 * GridEngine / SlidingWindowManager / EmergencyStopService 从这里读实时行情，
 * 避免走 REST。每个 symbol 一个 volatile 引用（BookTickerSubscriber 单写多读）。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MarketQuoteCache {

    private final MmProperties properties;

    private final ConcurrentMap<String, MarketQuote> quotes = new ConcurrentHashMap<>();

    /** BookTickerSubscriber 收到事件后更新 */
    public void update(MarketQuote quote) {
        if (quote == null || quote.getSymbol() == null) return;
        quotes.put(quote.getSymbol().toUpperCase(), quote);
    }

    /**
     * 读取最新报价。返回 null 表示无数据或数据过期。
     * 过期判定：当前时间 - updateTimeMs > cache TTL
     */
    public MarketQuote get(String symbol) {
        if (symbol == null) return null;
        MarketQuote q = quotes.get(symbol.toUpperCase());
        if (q == null) return null;
        long age = System.currentTimeMillis() - q.getUpdateTimeMs();
        if (age > properties.getBookTickerCacheTtlMs()) {
            log.debug("[MarketQuoteCache] symbol={} stale (age={}ms)", symbol, age);
            return null;
        }
        return q;
    }

    /** 强制读取，不做过期检查（用于诊断/告警） */
    public MarketQuote getRaw(String symbol) {
        return quotes.get(symbol.toUpperCase());
    }

    public int size() {
        return quotes.size();
    }
}

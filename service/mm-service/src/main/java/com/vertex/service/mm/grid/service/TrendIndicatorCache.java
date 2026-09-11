package com.vertex.service.mm.grid.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 每策略最新 ADX / ATR 缓存 —— AdaptiveScheduler 定时刷新，GridEngine 综合评分时读取。
 * <p>
 * 避免 GridEngine 在成交事件的热路径里重复计算 ADX（重）—— 用最近 5 分钟一次的
 * AdaptiveScheduler 结果就够（趋势判据不需要秒级精度）。
 * </p>
 */
@Slf4j
@Component
public class TrendIndicatorCache {

    private final ConcurrentMap<Long, IndicatorSnapshot> snapshots = new ConcurrentHashMap<>();

    public void putAdx(long strategyId, BigDecimal adx) {
        IndicatorSnapshot s = snapshots.computeIfAbsent(strategyId, k -> new IndicatorSnapshot());
        s.adx = adx;
        s.adxAt = System.currentTimeMillis();
    }

    /**
     * 拿最新 ADX。返回 null 表示未计算或过期（> 10 分钟 = 认为陈旧）。
     */
    public BigDecimal getAdx(long strategyId) {
        IndicatorSnapshot s = snapshots.get(strategyId);
        if (s == null || s.adx == null) return null;
        if (System.currentTimeMillis() - s.adxAt > 10 * 60_000L) return null;
        return s.adx;
    }

    public void clear(long strategyId) {
        snapshots.remove(strategyId);
    }

    private static class IndicatorSnapshot {
        volatile BigDecimal adx;
        volatile long adxAt;
    }
}

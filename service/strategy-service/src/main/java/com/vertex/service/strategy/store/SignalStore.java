package com.vertex.service.strategy.store;

import com.vertex.model.entity.strategy.Signal;

import java.util.List;

/**
 * 信号存储接口（RocksDB 快速查询）
 */
public interface SignalStore {

    /**
     * 保存信号
     */
    void save(Signal signal);

    /**
     * 按策略ID查询信号
     */
    List<Signal> query(Long strategyId, String exchange, String symbol,
                       Long startTime, Long endTime, int limit);

    /**
     * 获取策略最新信号
     */
    Signal getLatest(Long strategyId);
}

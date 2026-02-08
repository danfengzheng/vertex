package com.vertex.service.strategy.store;

import com.alibaba.fastjson2.JSON;
import com.vertex.common.core.exception.BizException;
import com.vertex.common.core.GlobalError;
import com.vertex.model.entity.strategy.Signal;
import com.vertex.service.quote.store.RocksDBManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.rocksdb.RocksDBException;

import java.util.List;
import java.util.Map;

/**
 * 基于 RocksDB 的信号存储实现
 * <p>
 * Key 格式: {strategyId}:{exchange}:{symbol}:{interval}:{paddedTimestamp}
 * </p>
 */
@Slf4j
@RequiredArgsConstructor
public class RocksDBSignalStore implements SignalStore {

    private static final int TIMESTAMP_PAD_LENGTH = 19;

    private final RocksDBManager rocksDBManager;

    @Override
    public void save(Signal signal) {
        String key = buildKey(signal.getStrategyId(), signal.getExchange(),
                signal.getSymbol(), signal.getInterval().getCode(), signal.getSignalTime());
        try {
            rocksDBManager.put(key, JSON.toJSONBytes(signal));
        } catch (RocksDBException e) {
            log.error("Failed to save signal to RocksDB, key: {}", key, e);
            throw new BizException(GlobalError.SIGNAL_STORE_ERROR);
        }
    }

    @Override
    public List<Signal> query(Long strategyId, String exchange, String symbol,
                              Long startTime, Long endTime, int limit) {
        String prefix = strategyId + ":" + exchange + ":" + symbol + ":";
        String startKey = startTime != null ? prefix + padTimestamp(startTime) : null;
        String endKey = endTime != null ? prefix + padTimestamp(endTime) : null;

        List<Map.Entry<String, byte[]>> entries = rocksDBManager.rangeQuery(prefix, startKey, endKey, limit);
        return entries.stream()
                .map(entry -> JSON.parseObject(entry.getValue(), Signal.class))
                .toList();
    }

    @Override
    public Signal getLatest(Long strategyId) {
        String prefix = strategyId + ":";
        Map.Entry<String, byte[]> entry = rocksDBManager.getLatest(prefix);
        if (entry == null) {
            return null;
        }
        return JSON.parseObject(entry.getValue(), Signal.class);
    }

    private String buildKey(Long strategyId, String exchange, String symbol,
                            String interval, Long timestamp) {
        return strategyId + ":" + exchange + ":" + symbol + ":" + interval + ":" + padTimestamp(timestamp);
    }

    private String padTimestamp(Long timestamp) {
        return String.format("%" + TIMESTAMP_PAD_LENGTH + "d", timestamp).replace(' ', '0');
    }
}

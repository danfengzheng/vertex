package com.vertex.service.quote.store;

import com.alibaba.fastjson2.JSON;
import com.vertex.common.core.GlobalError;
import com.vertex.common.core.exception.BizException;
import com.vertex.model.entity.quote.KLine;
import com.vertex.model.entity.quote.KLineInterval;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.rocksdb.RocksDBException;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 基于 RocksDB 的 K线存储实现
 * <p>
 * Key 设计：{exchange}:{symbol}:{interval}:{openTime(固定19位)}
 * 示例：binance:BTC-USDT:1h:0000001706745600000
 */
@Slf4j
@RequiredArgsConstructor
public class RocksDBKLineStore implements KLineStore {

    private static final int TIMESTAMP_LENGTH = 19;

    private final RocksDBManager rocksDBManager;

    @Override
    public void save(KLine kline) {
        try {
            String key = buildKey(kline);
            byte[] value = JSON.toJSONBytes(kline);
            rocksDBManager.put(key, value);
        } catch (RocksDBException e) {
            log.error("Failed to save KLine: {}", kline, e);
            throw new BizException(GlobalError.KLINE_STORE_ERROR);
        }
    }

    @Override
    public void saveBatch(List<KLine> klines) {
        try {
            Map<String, byte[]> entries = new LinkedHashMap<>();
            for (KLine kline : klines) {
                entries.put(buildKey(kline), JSON.toJSONBytes(kline));
            }
            rocksDBManager.putBatch(entries);
        } catch (RocksDBException e) {
            log.error("Failed to save KLine batch, size: {}", klines.size(), e);
            throw new BizException(GlobalError.KLINE_STORE_ERROR);
        }
    }

    @Override
    public List<KLine> query(String exchange, String symbol, KLineInterval interval,
                             Long startTime, Long endTime, int limit) {
        String prefix = buildPrefix(exchange, symbol, interval);
        String startKey = startTime != null ? prefix + padTimestamp(startTime) : null;
        String endKey = endTime != null ? prefix + padTimestamp(endTime) : null;

        List<Map.Entry<String, byte[]>> entries = rocksDBManager.rangeQuery(prefix, startKey, endKey, limit);

        return entries.stream()
                .map(entry -> JSON.parseObject(entry.getValue(), KLine.class))
                .collect(Collectors.toList());
    }

    @Override
    public KLine getLatest(String exchange, String symbol, KLineInterval interval) {
        String prefix = buildPrefix(exchange, symbol, interval);
        Map.Entry<String, byte[]> entry = rocksDBManager.getLatest(prefix);
        if (entry == null) {
            return null;
        }
        return JSON.parseObject(entry.getValue(), KLine.class);
    }

    /**
     * 构建完整 Key
     */
    private String buildKey(KLine kline) {
        return buildPrefix(kline.getExchange(), kline.getSymbol(), kline.getInterval())
                + padTimestamp(kline.getOpenTime());
    }

    /**
     * 构建 Key 前缀（不含时间戳）
     */
    private String buildPrefix(String exchange, String symbol, KLineInterval interval) {
        return exchange + ":" + symbol + ":" + interval.getCode() + ":";
    }

    /**
     * 将时间戳填充为固定 19 位字符串，保证字典序 = 时间序
     */
    private String padTimestamp(long timestamp) {
        return String.format("%0" + TIMESTAMP_LENGTH + "d", timestamp);
    }
}

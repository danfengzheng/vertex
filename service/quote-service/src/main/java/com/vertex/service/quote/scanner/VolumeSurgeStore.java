package com.vertex.service.quote.scanner;

import com.alibaba.fastjson2.JSON;
import com.vertex.service.quote.store.RocksDBManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.rocksdb.RocksDBException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 成交量暴增告警的 RocksDB 持久化层。
 * <p>
 * key 前缀：
 * <pre>
 *   vsurge:cd:{exchange}:{symbol}      → 冷却期结束时刻 (epoch ms 的字符串)
 *   vsurge:hist:{alertedAt-19}:{sym}   → VolumeSurgeAlert JSON（时序倒排列表）
 * </pre>
 * alertedAt 存成 19 位定宽字符串，保证 RocksDB 字典序 = 时间顺序，
 * 反向 prefix scan 可以直接拿"最近 N 条"。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "vertex.quote.volume-surge", name = "enabled", havingValue = "true")
public class VolumeSurgeStore {

    private static final String PREFIX_CD = "vsurge:cd:";
    private static final String PREFIX_HIST = "vsurge:hist:";
    private static final String PREFIX_CURSOR = "vsurge:cursor:";     // "已缓存到哪一根 K 线 openTime"
    private static final String KEY_EXINFO_JSON = "vsurge:exinfo:binance:json";  // exchangeInfo → symbol→onboardDate 缓存
    private static final String KEY_EXINFO_TS = "vsurge:exinfo:binance:ts";      // 上次刷新时刻
    private static final int TS_WIDTH = 19; // Long.MAX_VALUE = 19 位

    private final RocksDBManager rocksDB;

    /**
     * 若 symbol 处于冷却期，返回冷却期结束时刻（epoch ms）；否则返回 0。
     */
    public long getCooldownEnd(String exchange, String symbol) {
        try {
            byte[] bytes = rocksDB.get(PREFIX_CD + exchange + ":" + symbol);
            if (bytes == null) return 0L;
            return Long.parseLong(new String(bytes));
        } catch (Exception e) {
            log.warn("[VolumeSurgeStore] getCooldownEnd failed for {}: {}", symbol, e.getMessage());
            return 0L;
        }
    }

    /** 设置 symbol 冷却期结束时刻 */
    public void setCooldownEnd(String exchange, String symbol, long endMs) {
        try {
            rocksDB.put(PREFIX_CD + exchange + ":" + symbol, Long.toString(endMs).getBytes());
        } catch (RocksDBException e) {
            log.warn("[VolumeSurgeStore] setCooldownEnd failed for {}: {}", symbol, e.getMessage());
        }
    }

    /** 保存一条告警到时序倒排列表 */
    public void saveAlert(VolumeSurgeAlert alert) {
        try {
            String key = PREFIX_HIST + pad(alert.getAlertedAt()) + ":" + alert.getSymbol();
            rocksDB.put(key, JSON.toJSONBytes(alert));
        } catch (RocksDBException e) {
            log.warn("[VolumeSurgeStore] saveAlert failed for {}: {}",
                    alert.getSymbol(), e.getMessage());
        }
    }

    /** 拉最近 limit 条告警（按 alertedAt 倒序） */
    public List<VolumeSurgeAlert> listRecent(int limit) {
        int cap = Math.max(1, Math.min(limit, 1000));
        try {
            List<Map.Entry<String, byte[]>> entries =
                    rocksDB.rangeQueryReverse(PREFIX_HIST, null, null, cap);
            List<VolumeSurgeAlert> out = new ArrayList<>(entries.size());
            for (Map.Entry<String, byte[]> e : entries) {
                try {
                    out.add(JSON.parseObject(e.getValue(), VolumeSurgeAlert.class));
                } catch (Exception parse) {
                    log.warn("[VolumeSurgeStore] parse alert failed: key={}", e.getKey());
                }
            }
            return out;
        } catch (Exception e) {
            log.warn("[VolumeSurgeStore] listRecent failed: {}", e.getMessage());
            return List.of();
        }
    }

    // ─── K 线增量缓存游标 ─────────────────────────────────────
    //
    // 每 symbol 一个游标，记录"KLineStore 里最新一根 1H K 线的 openTime"。
    // Scanner 下次扫描时，只需要从 cursor + 1H 拉到 now，通常只有 1-2 根新 K 线。
    //
    // 首次扫描（无游标）→ 拉 baselineHours + 2 根；之后走增量。

    /** 读游标；未设置返回 0 */
    public long getCachedCursor(String exchange, String symbol) {
        try {
            byte[] bytes = rocksDB.get(PREFIX_CURSOR + exchange + ":" + symbol);
            if (bytes == null) return 0L;
            return Long.parseLong(new String(bytes));
        } catch (Exception e) {
            log.warn("[VolumeSurgeStore] getCursor failed for {}: {}", symbol, e.getMessage());
            return 0L;
        }
    }

    /** 写游标 */
    public void setCachedCursor(String exchange, String symbol, long openTime) {
        try {
            rocksDB.put(PREFIX_CURSOR + exchange + ":" + symbol, Long.toString(openTime).getBytes());
        } catch (RocksDBException e) {
            log.warn("[VolumeSurgeStore] setCursor failed for {}: {}", symbol, e.getMessage());
        }
    }

    // ─── exchangeInfo 持久化缓存 ──────────────────────────────
    //
    // 存 exchangeInfo 的 JSON 快照 + 刷新时间；重启后立即可用，避免每次都打 REST。

    /** 保存 exchangeInfo 缓存（JSON 字符串 = symbol → onboardDate map） */
    public void saveExchangeInfo(String jsonString, long refreshedAt) {
        try {
            rocksDB.put(KEY_EXINFO_JSON, jsonString.getBytes());
            rocksDB.put(KEY_EXINFO_TS, Long.toString(refreshedAt).getBytes());
        } catch (RocksDBException e) {
            log.warn("[VolumeSurgeStore] saveExchangeInfo failed: {}", e.getMessage());
        }
    }

    /** 读 exchangeInfo 缓存；返回 null = 无缓存 */
    public ExchangeInfoCache loadExchangeInfo() {
        try {
            byte[] json = rocksDB.get(KEY_EXINFO_JSON);
            byte[] ts = rocksDB.get(KEY_EXINFO_TS);
            if (json == null || ts == null) return null;
            long refreshedAt = Long.parseLong(new String(ts));
            return new ExchangeInfoCache(new String(json), refreshedAt);
        } catch (Exception e) {
            log.warn("[VolumeSurgeStore] loadExchangeInfo failed: {}", e.getMessage());
            return null;
        }
    }

    /** exchangeInfo 持久化的两个字段 */
    public record ExchangeInfoCache(String jsonString, long refreshedAt) {}

    /** 把 epoch ms 左补 0 到 19 位，保证字典序与数值序一致 */
    private static String pad(long ms) {
        String s = Long.toString(ms);
        if (s.length() >= TS_WIDTH) return s;
        StringBuilder b = new StringBuilder(TS_WIDTH);
        for (int i = 0; i < TS_WIDTH - s.length(); i++) b.append('0');
        b.append(s);
        return b.toString();
    }
}

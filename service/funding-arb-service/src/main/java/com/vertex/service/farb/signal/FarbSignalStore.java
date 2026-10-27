package com.vertex.service.farb.signal;

import com.alibaba.fastjson2.JSON;
import com.vertex.service.quote.store.RocksDBManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.rocksdb.RocksDBException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 资金费套利 - 信号时序 RocksDB 持久化层。
 * <p>
 * 复用 quote-service 的 {@link RocksDBManager}（单 CF + key 前缀分区），对齐 AiAnalysisStore 模板。
 * <pre>
 *   farb:funding:{symbol:12}:{settleTs:8}   → JSON {rate, intervalHours}
 *   farb:prem:{symbol:12}:{ts:8}            → JSON {markPrice, indexPrice, fundingRate, nextSettleTs}
 *   farb:oi:{symbol:12}:{ts:8}              → JSON {openInterest}
 *   farb:lsr:{symbol:12}:{ts:8}             → JSON {longShortRatio, longQty, shortQty}
 *   farb:tbsr:{symbol:12}:{ts:8}            → JSON {buyVol, sellVol}
 *   farb:vol:{symbol:12}:{ts:8}             → JSON {vol24h, vol72h, vol7d}
 * </pre>
 * <b>Key 设计要点</b>：symbol padding 到 12 字符 + 时间戳 big-endian 10 位十进制补零，
 * 保证字典序 = 时间序，range scan 天然对齐。
 * </p>
 *
 * @see com.vertex.service.farb.signal.FarbDataCollector
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FarbSignalStore {

    public static final String PFX_FUNDING = "farb:funding:";
    public static final String PFX_PREM    = "farb:prem:";
    public static final String PFX_OI      = "farb:oi:";
    public static final String PFX_LSR     = "farb:lsr:";
    public static final String PFX_TBSR    = "farb:tbsr:";
    public static final String PFX_VOL     = "farb:vol:";

    /** symbol padding 长度（BTCUSDT=7 → 补 5 空格；LONGSYMBOLUSDT=14 → 截断到 12 也可，取决于业务）*/
    static final int SYMBOL_PAD = 12;
    /** timestamp（ms）十进制补零长度：1970-2099 都是 13 位；用 15 位留缓冲 */
    static final int TS_WIDTH = 15;

    private final RocksDBManager rocksDB;

    // ─── 写入 ────────────────────────────────────────────────────────────────

    public void save(String prefix, String symbol, long ts, Object value) {
        String key = key(prefix, symbol, ts);
        try {
            rocksDB.put(key, JSON.toJSONBytes(value));
        } catch (RocksDBException e) {
            log.warn("[FarbSignalStore] save failed key={} err={}", key, e.getMessage());
        }
    }

    // ─── 精确点查 ────────────────────────────────────────────────────────────

    public <T> T get(String prefix, String symbol, long ts, Class<T> clazz) {
        String key = key(prefix, symbol, ts);
        try {
            byte[] bytes = rocksDB.get(key);
            return bytes == null ? null : JSON.parseObject(bytes, clazz);
        } catch (RocksDBException e) {
            log.warn("[FarbSignalStore] get failed key={} err={}", key, e.getMessage());
            return null;
        }
    }

    // ─── 按 symbol range scan ────────────────────────────────────────────────

    /**
     * 按时间倒序拉最近 N 条。
     * <p>使用 seekForPrev 从 upperBound 开始反向遍历。这里简化为正向拉全区间 + Collections.reverse，
     * 因为单 symbol N 通常 ≤ 100，性能可接受。</p>
     */
    public <T> List<T> scanRecent(String prefix, String symbol, int limit, Class<T> clazz) {
        String startKey = key(prefix, symbol, 0L);
        String endKey   = key(prefix, symbol, Long.MAX_VALUE);
        List<Map.Entry<String, byte[]>> raw = rocksDB.rangeQuery(prefix + pad(symbol) + ":",
                startKey, endKey, Integer.MAX_VALUE);
        List<T> results = new ArrayList<>(Math.min(limit, raw.size()));
        // 拿末尾 limit 条（rangeQuery 已按字典序 = 时间序升序）
        int from = Math.max(0, raw.size() - limit);
        for (int i = from; i < raw.size(); i++) {
            try {
                results.add(JSON.parseObject(raw.get(i).getValue(), clazz));
            } catch (Exception ex) {
                log.warn("[FarbSignalStore] parse failed key={}", raw.get(i).getKey());
            }
        }
        return results;
    }

    /**
     * 按时间范围拉。
     */
    public <T> List<T> scanByRange(String prefix, String symbol, long fromTs, long toTs, Class<T> clazz) {
        String startKey = key(prefix, symbol, fromTs);
        String endKey   = key(prefix, symbol, toTs);
        List<Map.Entry<String, byte[]>> raw = rocksDB.rangeQuery(prefix + pad(symbol) + ":",
                startKey, endKey, Integer.MAX_VALUE);
        List<T> results = new ArrayList<>(raw.size());
        for (var e : raw) {
            try {
                results.add(JSON.parseObject(e.getValue(), clazz));
            } catch (Exception ex) {
                log.warn("[FarbSignalStore] parse failed key={}", e.getKey());
            }
        }
        return results;
    }

    /**
     * 按时间范围拉，同时返回 timestamp（从 key 尾部提取）。
     * 用于回测：需要真实结算时间戳而非近似。
     */
    public <T> List<TsValue<T>> scanByRangeWithTs(String prefix, String symbol,
                                                    long fromTs, long toTs, Class<T> clazz) {
        String startKey = key(prefix, symbol, fromTs);
        String endKey   = key(prefix, symbol, toTs);
        List<Map.Entry<String, byte[]>> raw = rocksDB.rangeQuery(prefix + pad(symbol) + ":",
                startKey, endKey, Integer.MAX_VALUE);
        List<TsValue<T>> results = new ArrayList<>(raw.size());
        for (var e : raw) {
            try {
                long ts = extractTs(e.getKey());
                results.add(new TsValue<>(ts, JSON.parseObject(e.getValue(), clazz)));
            } catch (Exception ex) {
                log.warn("[FarbSignalStore] parse failed key={}", e.getKey());
            }
        }
        return results;
    }

    /** 带时间戳的返回条目 */
    public record TsValue<T>(long ts, T value) {}

    // ─── 清理 ────────────────────────────────────────────────────────────────

    /**
     * 删除 prefix 下时间戳 < olderThan 的数据。
     * <p>简化实现：range scan + delete。数据量大时可用 RocksDB deleteRange 优化。</p>
     */
    public int purgeExpired(String prefix, long olderThanMs) {
        // 全 symbol 范围扫描（按 prefix）
        List<Map.Entry<String, byte[]>> raw = rocksDB.rangeQuery(prefix, null, null, Integer.MAX_VALUE);
        int deleted = 0;
        for (var e : raw) {
            long ts = extractTs(e.getKey());
            if (ts < olderThanMs) {
                try {
                    rocksDB.delete(e.getKey());
                    deleted++;
                } catch (RocksDBException ex) {
                    log.warn("[FarbSignalStore] purge delete failed key={} err={}", e.getKey(), ex.getMessage());
                }
            }
        }
        if (deleted > 0) {
            log.info("[FarbSignalStore] purged {} entries under prefix={} olderThan={}",
                    deleted, prefix, olderThanMs);
        }
        return deleted;
    }

    // ─── 内部：key 编码 ──────────────────────────────────────────────────────

    /**
     * 组装 key：{prefix}{symbol padding 12}:{ts padding 15 decimal digits}
     */
    static String key(String prefix, String symbol, long ts) {
        return prefix + pad(symbol) + ":" + tsPad(ts);
    }

    private static String pad(String symbol) {
        if (symbol == null) return "            ";
        if (symbol.length() >= SYMBOL_PAD) return symbol.substring(0, SYMBOL_PAD);
        StringBuilder sb = new StringBuilder(SYMBOL_PAD);
        sb.append(symbol);
        while (sb.length() < SYMBOL_PAD) sb.append(' ');
        return sb.toString();
    }

    private static String tsPad(long ts) {
        String s = Long.toString(Math.max(0, ts));
        if (s.length() >= TS_WIDTH) return s;
        StringBuilder sb = new StringBuilder(TS_WIDTH);
        for (int i = s.length(); i < TS_WIDTH; i++) sb.append('0');
        sb.append(s);
        return sb.toString();
    }

    private static long extractTs(String key) {
        // key 末尾 15 位数字为 ts
        try {
            String tail = key.substring(key.length() - TS_WIDTH);
            return Long.parseLong(tail);
        } catch (Exception ex) {
            return 0;
        }
    }
}

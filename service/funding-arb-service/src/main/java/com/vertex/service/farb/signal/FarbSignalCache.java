package com.vertex.service.farb.signal;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 资金费套利 - 信号内存 cache。
 * <p>
 * 从 RocksDB 冷启动填充，运行时 {@link FarbDataCollector} 每次拉取后 append。
 * RegimeScanner 直接查内存无 IO 延迟。
 * </p>
 * <ul>
 *   <li>Funding history: 每 symbol 最近 100 次结算</li>
 *   <li>Premium (含 fundingRate/basis): 每 symbol 最近 288 点（30s × 288 = 2.4h）</li>
 *   <li>OI / LSR: 每 symbol 最近 288 点（5min × 288 = 24h）</li>
 *   <li>Taker BSR: 每 symbol 最近 96 点（15min × 96 = 24h）</li>
 *   <li>Vol: 每 symbol 最近 24 点（60min × 24 = 24h）</li>
 * </ul>
 *
 * 每 symbol 每指标一个 fixed-size deque，使用 synchronized 简单保护（写频率低，读频率高，冲突极少）。
 */
@Slf4j
@Component
public class FarbSignalCache {

    // 每种指标独立的容量上限
    public static final int FUNDING_HISTORY_LIMIT = 100;
    public static final int PREMIUM_LIMIT         = 288;
    public static final int OI_LIMIT              = 288;
    public static final int LSR_LIMIT             = 288;
    public static final int TBSR_LIMIT            = 96;
    public static final int VOL_LIMIT             = 24;

    /** 通用条目 —— 携带时间戳，方便按时间过滤 */
    public record Entry<V>(long ts, V value) {}

    // symbol → Deque<Entry<T>>，每个指标独立 map
    private final Map<String, Deque<Entry<FundingRecord>>>   funding  = new ConcurrentHashMap<>();
    private final Map<String, Deque<Entry<PremiumRecord>>>   premium  = new ConcurrentHashMap<>();
    private final Map<String, Deque<Entry<OiRecord>>>        oi       = new ConcurrentHashMap<>();
    private final Map<String, Deque<Entry<LsrRecord>>>       lsr      = new ConcurrentHashMap<>();
    private final Map<String, Deque<Entry<TakerBsrRecord>>>  tbsr     = new ConcurrentHashMap<>();
    private final Map<String, Deque<Entry<VolRecord>>>       vol      = new ConcurrentHashMap<>();

    // ─── 数据类型 ─────────────────────────────────────────────────────────

    public record FundingRecord(double rate, int intervalHours) {}
    public record PremiumRecord(double markPrice, double indexPrice, double fundingRate, long nextSettleTs) {
        /** basis in bps: (mark - index) / index × 10000 */
        public double basisBps() {
            return indexPrice > 0 ? (markPrice - indexPrice) / indexPrice * 10000.0 : 0.0;
        }
    }
    public record OiRecord(double openInterest) {}
    public record LsrRecord(double longShortRatio, double longAccount, double shortAccount) {}
    public record TakerBsrRecord(double buySellRatio, double buyVol, double sellVol) {}
    public record VolRecord(double vol24h, double vol72h, double vol7d) {}

    // ─── 写入 API（Puller 调用）───────────────────────────────────────────

    public void putFunding(String symbol, long ts, FundingRecord v) {
        appendBounded(funding, symbol, ts, v, FUNDING_HISTORY_LIMIT);
    }

    public void putPremium(String symbol, long ts, PremiumRecord v) {
        appendBounded(premium, symbol, ts, v, PREMIUM_LIMIT);
    }

    public void putOi(String symbol, long ts, OiRecord v) {
        appendBounded(oi, symbol, ts, v, OI_LIMIT);
    }

    public void putLsr(String symbol, long ts, LsrRecord v) {
        appendBounded(lsr, symbol, ts, v, LSR_LIMIT);
    }

    public void putTbsr(String symbol, long ts, TakerBsrRecord v) {
        appendBounded(tbsr, symbol, ts, v, TBSR_LIMIT);
    }

    public void putVol(String symbol, long ts, VolRecord v) {
        appendBounded(vol, symbol, ts, v, VOL_LIMIT);
    }

    // ─── 读取 API（Scanner 调用）──────────────────────────────────────────

    public List<Entry<FundingRecord>> recentFunding(String symbol, int n) {
        return tailN(funding.get(symbol), n);
    }

    public Entry<PremiumRecord> latestPremium(String symbol) {
        return last(premium.get(symbol));
    }

    /** 拿指定时间之前最接近的一个 premium（用于计算 basis_delta_2h）*/
    public Entry<PremiumRecord> premiumAt(String symbol, long tsCutoff) {
        Deque<Entry<PremiumRecord>> d = premium.get(symbol);
        if (d == null) return null;
        synchronized (d) {
            Entry<PremiumRecord> best = null;
            for (var e : d) {
                if (e.ts() <= tsCutoff) best = e;
                else break;
            }
            return best;
        }
    }

    public Entry<OiRecord> latestOi(String symbol) { return last(oi.get(symbol)); }
    public Entry<OiRecord> oiAt(String symbol, long tsCutoff) {
        return findAt(oi.get(symbol), tsCutoff);
    }

    public Entry<LsrRecord> latestLsr(String symbol) { return last(lsr.get(symbol)); }
    public Entry<TakerBsrRecord> latestTbsr(String symbol) { return last(tbsr.get(symbol)); }
    public Entry<VolRecord> latestVol(String symbol) { return last(vol.get(symbol)); }

    // ─── 状态观测（前端/事件日志用）───────────────────────────────────────

    public int fundingSize(String symbol) { return size(funding.get(symbol)); }
    public int premiumSize(String symbol) { return size(premium.get(symbol)); }
    public int oiSize(String symbol)      { return size(oi.get(symbol)); }
    public int lsrSize(String symbol)     { return size(lsr.get(symbol)); }
    public int tbsrSize(String symbol)    { return size(tbsr.get(symbol)); }
    public int volSize(String symbol)     { return size(vol.get(symbol)); }

    // ─── 内部：通用工具 ─────────────────────────────────────────────────

    private <V> void appendBounded(Map<String, Deque<Entry<V>>> map, String symbol, long ts, V v, int limit) {
        Deque<Entry<V>> d = map.computeIfAbsent(symbol, s -> new ArrayDeque<>(limit));
        synchronized (d) {
            // 保持时序单调：晚到的旧点被抛弃
            if (!d.isEmpty() && d.peekLast().ts() >= ts) {
                return;
            }
            d.addLast(new Entry<>(ts, v));
            while (d.size() > limit) d.pollFirst();
        }
    }

    private <V> List<Entry<V>> tailN(Deque<Entry<V>> d, int n) {
        if (d == null || n <= 0) return List.of();
        synchronized (d) {
            int total = d.size();
            if (total <= n) return new ArrayList<>(d);
            List<Entry<V>> res = new ArrayList<>(n);
            int skip = total - n;
            int i = 0;
            for (var e : d) {
                if (i++ < skip) continue;
                res.add(e);
            }
            return res;
        }
    }

    private <V> Entry<V> last(Deque<Entry<V>> d) {
        if (d == null) return null;
        synchronized (d) {
            return d.isEmpty() ? null : d.peekLast();
        }
    }

    private <V> Entry<V> findAt(Deque<Entry<V>> d, long tsCutoff) {
        if (d == null) return null;
        synchronized (d) {
            Entry<V> best = null;
            for (var e : d) {
                if (e.ts() <= tsCutoff) best = e;
                else break;
            }
            return best;
        }
    }

    private int size(Deque<?> d) { return d == null ? 0 : d.size(); }

    // ─── 冷启动：从 RocksDB 一次性 hydrate ────────────────────────────────

    /**
     * 从 RocksDB 批量加载。在应用启动、DataCollector 首次拉取前调用。
     * 每个 symbol 每种信号加载最近 N 条（各自 limit）。
     */
    public int hydrateFromStore(FarbSignalStore store, java.util.Collection<String> symbols) {
        int total = 0;
        for (String sym : symbols) {
            total += hydrateOne(store, sym, FarbSignalStore.PFX_FUNDING, FundingRecord.class,
                    FUNDING_HISTORY_LIMIT, this::putFundingSilent);
            total += hydrateOne(store, sym, FarbSignalStore.PFX_PREM, PremiumRecord.class,
                    PREMIUM_LIMIT, this::putPremiumSilent);
            total += hydrateOne(store, sym, FarbSignalStore.PFX_OI, OiRecord.class,
                    OI_LIMIT, this::putOiSilent);
            total += hydrateOne(store, sym, FarbSignalStore.PFX_LSR, LsrRecord.class,
                    LSR_LIMIT, this::putLsrSilent);
            total += hydrateOne(store, sym, FarbSignalStore.PFX_TBSR, TakerBsrRecord.class,
                    TBSR_LIMIT, this::putTbsrSilent);
            total += hydrateOne(store, sym, FarbSignalStore.PFX_VOL, VolRecord.class,
                    VOL_LIMIT, this::putVolSilent);
        }
        log.info("[FarbSignalCache] hydrated {} entries across {} symbols", total, symbols.size());
        return total;
    }

    private <V> int hydrateOne(FarbSignalStore store, String sym, String prefix, Class<V> clazz,
                                int limit, TriConsumer<String, Long, V> putter) {
        // 简化：只按 symbol 拉近 N 条，忽略 timestamp（RocksDB store 内部会返回按序结果）
        // 这里因 store API 不返回 timestamp，暂用当前时间作为 fallback。RegimeScanner 后续会用最新 puller 覆盖。
        List<V> vals = store.scanRecent(prefix, sym, limit, clazz);
        long baseTs = System.currentTimeMillis();
        for (int i = 0; i < vals.size(); i++) {
            // 分散时间戳：i 越前越早（RocksDB scanRecent 已按升序返回）
            long ts = baseTs - (vals.size() - i) * 1000L;
            putter.accept(sym, ts, vals.get(i));
        }
        return vals.size();
    }

    // 静默 put（不 log；hydrate 阶段用）
    private void putFundingSilent(String s, long ts, FundingRecord v) { putFunding(s, ts, v); }
    private void putPremiumSilent(String s, long ts, PremiumRecord v) { putPremium(s, ts, v); }
    private void putOiSilent(String s, long ts, OiRecord v)            { putOi(s, ts, v); }
    private void putLsrSilent(String s, long ts, LsrRecord v)          { putLsr(s, ts, v); }
    private void putTbsrSilent(String s, long ts, TakerBsrRecord v)    { putTbsr(s, ts, v); }
    private void putVolSilent(String s, long ts, VolRecord v)          { putVol(s, ts, v); }

    @FunctionalInterface
    private interface TriConsumer<A, B, C> {
        void accept(A a, B b, C c);
    }

    /** 已 tracked 的所有 symbol（premium 有值即视为 tracked，最简单口径）*/
    public java.util.Set<String> trackedSymbols() {
        return Collections.unmodifiableSet(premium.keySet());
    }
}

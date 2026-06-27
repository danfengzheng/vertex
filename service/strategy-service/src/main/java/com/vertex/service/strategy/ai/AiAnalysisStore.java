package com.vertex.service.strategy.ai;

import com.alibaba.fastjson2.JSON;
import com.vertex.model.vo.ai.AiBacktestAnalysisProgress;
import com.vertex.model.vo.ai.AiSignalAnalysis;
import com.vertex.model.vo.ai.AiTradeAnalysis;
import com.vertex.model.vo.strategy.BacktestResultVO;
import com.vertex.service.quote.store.RocksDBManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.rocksdb.RocksDBException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * AI 分析结果的 RocksDB 持久化层。
 * <p>
 * 复用 quote-service 的 {@link RocksDBManager}（单 CF + key 前缀分区）：
 * <pre>
 *   ai:rt:{signalId}                          → AiSignalAnalysis JSON
 *   ai:bt:res:{cacheKey}                      → BacktestResultVO JSON  （回测结果缓存）
 *   ai:bt:trade:{cacheKey}:{tradeIdx 4位补0}  → AiTradeAnalysis JSON
 *   ai:bt:prog:{cacheKey}                     → AiBacktestAnalysisProgress JSON
 * </pre>
 * 不引入新 ColumnFamily，所有 key 走默认 CF，前缀范围扫描即可遍历同类。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiAnalysisStore {

    private static final String PREFIX_RT       = "ai:rt:";
    private static final String PREFIX_BT_RES   = "ai:bt:res:";
    private static final String PREFIX_BT_TRADE = "ai:bt:trade:";
    private static final String PREFIX_BT_PROG  = "ai:bt:prog:";
    /** trade index 补零到 4 位，确保按字典序与数值序一致（最多 9999 笔/回测） */
    private static final int TRADE_IDX_WIDTH = 4;

    private final RocksDBManager rocksDB;

    // ─── 实时信号分析 ──────────────────────────────────────────────

    public void saveSignalAnalysis(long signalId, AiSignalAnalysis analysis) {
        try {
            rocksDB.put(PREFIX_RT + signalId,
                    JSON.toJSONBytes(analysis));
        } catch (RocksDBException e) {
            log.warn("[AiStore] save signal analysis failed: signalId={}, err={}", signalId, e.getMessage());
        }
    }

    public AiSignalAnalysis getSignalAnalysis(long signalId) {
        try {
            byte[] bytes = rocksDB.get(PREFIX_RT + signalId);
            return bytes == null ? null : JSON.parseObject(bytes, AiSignalAnalysis.class);
        } catch (RocksDBException e) {
            log.warn("[AiStore] get signal analysis failed: signalId={}, err={}", signalId, e.getMessage());
            return null;
        }
    }

    /** 删除单条信号的 AI 分析（手动重新触发时清掉旧结果）。*/
    public void deleteSignalAnalysis(long signalId) {
        try {
            rocksDB.delete(PREFIX_RT + signalId);
        } catch (RocksDBException e) {
            log.warn("[AiStore] delete signal analysis failed: signalId={}, err={}", signalId, e.getMessage());
        }
    }

    /**
     * 列表查询：扫描所有 ai:rt:* key，反序列化成 (signalId, analysis) 对，
     * 按 analyzedAt 降序返回最近 limit 条。
     * <p>
     * RocksDB key 是 {@code ai:rt:{signalId}}，signalId 用十进制字符串存，
     * 字典序与数值序不一致，所以扫到内存里按 analyzedAt 排一次再 cap。
     * 总量预期 << 10k，性能可忽略。
     * </p>
     */
    public List<Map.Entry<Long, AiSignalAnalysis>> listSignalAnalyses(int limit) {
        // 扫描足够多再排序裁剪：cap 在 limit * 4 上限，避免极端情况吃内存
        int scanLimit = Math.min(Math.max(limit * 4, 200), 5000);
        List<Map.Entry<String, byte[]>> entries = rocksDB.rangeQuery(PREFIX_RT, null, null, scanLimit);
        List<Map.Entry<Long, AiSignalAnalysis>> rows = new ArrayList<>(entries.size());
        for (Map.Entry<String, byte[]> e : entries) {
            try {
                Long signalId = Long.parseLong(e.getKey().substring(PREFIX_RT.length()));
                AiSignalAnalysis a = JSON.parseObject(e.getValue(), AiSignalAnalysis.class);
                if (a != null) {
                    rows.add(Map.entry(signalId, a));
                }
            } catch (Exception ex) {
                log.warn("[AiStore] parse signal analysis failed: key={}, err={}", e.getKey(), ex.getMessage());
            }
        }
        rows.sort((x, y) -> {
            long tx = x.getValue().getAnalyzedAt() != null ? x.getValue().getAnalyzedAt() : 0L;
            long ty = y.getValue().getAnalyzedAt() != null ? y.getValue().getAnalyzedAt() : 0L;
            return Long.compare(ty, tx);
        });
        return rows.size() > limit ? rows.subList(0, limit) : rows;
    }

    // ─── 回测结果缓存 ──────────────────────────────────────────────

    public void saveBacktestResult(String cacheKey, BacktestResultVO result) {
        try {
            rocksDB.put(PREFIX_BT_RES + cacheKey,
                    JSON.toJSONBytes(result));
        } catch (RocksDBException e) {
            log.warn("[AiStore] save backtest result failed: cacheKey={}, err={}", cacheKey, e.getMessage());
        }
    }

    public BacktestResultVO getBacktestResult(String cacheKey) {
        try {
            byte[] bytes = rocksDB.get(PREFIX_BT_RES + cacheKey);
            return bytes == null ? null : JSON.parseObject(bytes, BacktestResultVO.class);
        } catch (RocksDBException e) {
            log.warn("[AiStore] get backtest result failed: cacheKey={}, err={}", cacheKey, e.getMessage());
            return null;
        }
    }

    public void deleteBacktestResult(String cacheKey) {
        try {
            rocksDB.delete(PREFIX_BT_RES + cacheKey);
        } catch (RocksDBException e) {
            log.warn("[AiStore] delete backtest result failed: cacheKey={}", cacheKey);
        }
    }

    // ─── 回测 trade AI 分析 ────────────────────────────────────────

    public void saveTradeAnalysis(String cacheKey, int tradeIndex, AiTradeAnalysis analysis) {
        try {
            rocksDB.put(tradeKey(cacheKey, tradeIndex),
                    JSON.toJSONBytes(analysis));
        } catch (RocksDBException e) {
            log.warn("[AiStore] save trade analysis failed: cacheKey={}, idx={}", cacheKey, tradeIndex);
        }
    }

    public AiTradeAnalysis getTradeAnalysis(String cacheKey, int tradeIndex) {
        try {
            byte[] bytes = rocksDB.get(tradeKey(cacheKey, tradeIndex));
            return bytes == null ? null : JSON.parseObject(bytes, AiTradeAnalysis.class);
        } catch (RocksDBException e) {
            log.warn("[AiStore] get trade analysis failed: cacheKey={}, idx={}", cacheKey, tradeIndex);
            return null;
        }
    }

    /**
     * 批量读取一次回测下的所有 trade AI 分析。
     */
    public List<AiTradeAnalysis> listTradeAnalyses(String cacheKey) {
        String prefix = PREFIX_BT_TRADE + cacheKey + ":";
        List<Map.Entry<String, byte[]>> entries = rocksDB.rangeQuery(prefix, null, null, Integer.MAX_VALUE);
        List<AiTradeAnalysis> result = new ArrayList<>(entries.size());
        for (Map.Entry<String, byte[]> e : entries) {
            try {
                result.add(JSON.parseObject(e.getValue(), AiTradeAnalysis.class));
            } catch (Exception ex) {
                log.warn("[AiStore] parse trade analysis failed: key={}", e.getKey());
            }
        }
        return result;
    }

    /**
     * 列表查询：跨所有 cacheKey 扫描 ai:bt:trade:* key，按 analyzedAt 降序返回最近 limit 条。
     * Map.Entry 的 key 形如 {@code cacheKey:tradeIdx}，便于上层解析。
     */
    public List<TradeAnalysisEntry> listAllTradeAnalyses(int limit) {
        int scanLimit = Math.min(Math.max(limit * 4, 200), 5000);
        List<Map.Entry<String, byte[]>> entries = rocksDB.rangeQuery(PREFIX_BT_TRADE, null, null, scanLimit);
        List<TradeAnalysisEntry> rows = new ArrayList<>(entries.size());
        for (Map.Entry<String, byte[]> e : entries) {
            try {
                // 形如 ai:bt:trade:{cacheKey}:{idx 4位}
                String rest = e.getKey().substring(PREFIX_BT_TRADE.length());
                int lastColon = rest.lastIndexOf(':');
                if (lastColon <= 0) continue;
                String cacheKey = rest.substring(0, lastColon);
                int idx = Integer.parseInt(rest.substring(lastColon + 1));
                AiTradeAnalysis a = JSON.parseObject(e.getValue(), AiTradeAnalysis.class);
                if (a != null) {
                    rows.add(new TradeAnalysisEntry(cacheKey, idx, a));
                }
            } catch (Exception ex) {
                log.warn("[AiStore] parse trade analysis failed: key={}, err={}", e.getKey(), ex.getMessage());
            }
        }
        rows.sort((x, y) -> {
            long tx = x.analysis.getAnalyzedAt() != null ? x.analysis.getAnalyzedAt() : 0L;
            long ty = y.analysis.getAnalyzedAt() != null ? y.analysis.getAnalyzedAt() : 0L;
            return Long.compare(ty, tx);
        });
        return rows.size() > limit ? rows.subList(0, limit) : rows;
    }

    /** 跨回测 trade 分析的扫描结果条目（cacheKey + tradeIndex + AI 分析）。*/
    public static class TradeAnalysisEntry {
        public final String cacheKey;
        public final int tradeIndex;
        public final AiTradeAnalysis analysis;
        public TradeAnalysisEntry(String cacheKey, int tradeIndex, AiTradeAnalysis analysis) {
            this.cacheKey = cacheKey;
            this.tradeIndex = tradeIndex;
            this.analysis = analysis;
        }
    }

    /**
     * 删除指定回测下所有 trade AI 分析（清理用）。
     */
    public int deleteAllTradeAnalyses(String cacheKey) {
        String prefix = PREFIX_BT_TRADE + cacheKey + ":";
        List<Map.Entry<String, byte[]>> entries = rocksDB.rangeQuery(prefix, null, null, Integer.MAX_VALUE);
        int count = 0;
        for (Map.Entry<String, byte[]> e : entries) {
            try {
                rocksDB.delete(e.getKey());
                count++;
            } catch (RocksDBException ex) {
                // 忽略单条失败
            }
        }
        return count;
    }

    // ─── 回测 AI 分析进度 ──────────────────────────────────────────

    public void saveProgress(AiBacktestAnalysisProgress progress) {
        try {
            rocksDB.put(PREFIX_BT_PROG + progress.getCacheKey(),
                    JSON.toJSONBytes(progress));
        } catch (RocksDBException e) {
            log.warn("[AiStore] save progress failed: cacheKey={}", progress.getCacheKey());
        }
    }

    public AiBacktestAnalysisProgress getProgress(String cacheKey) {
        try {
            byte[] bytes = rocksDB.get(PREFIX_BT_PROG + cacheKey);
            return bytes == null ? null : JSON.parseObject(bytes, AiBacktestAnalysisProgress.class);
        } catch (RocksDBException e) {
            log.warn("[AiStore] get progress failed: cacheKey={}", cacheKey);
            return null;
        }
    }

    public void deleteProgress(String cacheKey) {
        try {
            rocksDB.delete(PREFIX_BT_PROG + cacheKey);
        } catch (RocksDBException e) {
            log.warn("[AiStore] delete progress failed: cacheKey={}", cacheKey);
        }
    }

    // ─── 辅助：trade 索引补零，保证 prefix 扫描按数值顺序 ─────────────

    private static String tradeKey(String cacheKey, int tradeIndex) {
        return PREFIX_BT_TRADE + cacheKey + ":" + padIndex(tradeIndex);
    }

    private static String padIndex(int idx) {
        String s = Integer.toString(idx);
        if (s.length() >= TRADE_IDX_WIDTH) return s;
        StringBuilder b = new StringBuilder(TRADE_IDX_WIDTH);
        for (int i = 0; i < TRADE_IDX_WIDTH - s.length(); i++) b.append('0');
        b.append(s);
        return b.toString();
    }

}

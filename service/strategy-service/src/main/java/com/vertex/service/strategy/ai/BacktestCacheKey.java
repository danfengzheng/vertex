package com.vertex.service.strategy.ai;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.vertex.model.dto.strategy.BacktestConfigDTO;
import com.vertex.model.entity.strategy.Strategy;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.TreeMap;

/**
 * 回测缓存 key 计算。
 * <p>
 * <b>原则</b>：只有「真正影响回测结果」的字段才进入哈希。元数据字段（name / description /
 * enabled / 时间戳 / createBy 等）不进入，避免无意义的缓存失效。
 * </p>
 * <p>
 * <b>规范化</b>：
 * <ul>
 *   <li>BigDecimal 用 {@code stripTrailingZeros().toPlainString()} 统一精度（"2.0"=="2.00"）。</li>
 *   <li>indicatorConfigs / exitIndicatorConfigs 是 JSON 字符串，先反序列化 →
 *       fastjson2 排序 key 输出 → 重新序列化，避免空格 / key 顺序差异。</li>
 *   <li>所有字段进 {@code TreeMap}，保证序列化字段顺序稳定。</li>
 *   <li>cacheKey = SHA256(snapshot JSON) 的 hex 串。</li>
 * </ul>
 * </p>
 * <p>
 * <b>引擎版本号</b>：BacktestService 逻辑变更（修语义/修 bug）时必须 bump 此版本号，
 * 否则旧缓存仍会命中，导致行为不一致。
 * </p>
 */
@Slf4j
public final class BacktestCacheKey {

    /**
     * 回测引擎版本号。BacktestService 重大变更时手工 +1（或换成 git commit hash 自动注入）。
     * 一旦改动，所有旧缓存自动失效，所有用户重新计算回测。
     */
    public static final String ENGINE_VERSION = "v1.0";

    private BacktestCacheKey() {}

    /**
     * 计算 cacheKey（SHA256 hex，64 字符）。
     */
    public static String compute(Strategy strategy, BacktestConfigDTO config) {
        TreeMap<String, Object> snapshot = new TreeMap<>();

        // ── 引擎版本号（关键） ────────────────────────────
        snapshot.put("__engineVersion", ENGINE_VERSION);

        // ── 策略白名单字段（按影响回测的字段全集） ────────
        snapshot.put("exchange",   strategy.getExchange());
        snapshot.put("symbol",     strategy.getSymbol());
        snapshot.put("interval",   strategy.getInterval() == null ? null : strategy.getInterval().name());
        snapshot.put("atrInterval", strategy.getAtrInterval() == null ? null : strategy.getAtrInterval().name());
        snapshot.put("indicatorConfigs", normalizeJsonArray(strategy.getIndicatorConfigs()));
        snapshot.put("exitIndicatorConfigs", normalizeJsonArray(strategy.getExitIndicatorConfigs()));
        snapshot.put("maxHoldingBars", strategy.getMaxHoldingBars());
        snapshot.put("minSignalStrength", strategy.getMinSignalStrength());

        // 仓位与杠杆
        snapshot.put("positionSizing", strategy.getPositionSizing() == null ? null : strategy.getPositionSizing().name());
        snapshot.put("tradeQuantity", normBd(strategy.getTradeQuantity()));
        snapshot.put("positionRatio", normBd(strategy.getPositionRatio()));
        snapshot.put("initialCapital", normBd(strategy.getInitialCapital()));
        snapshot.put("leverage", strategy.getLeverage());
        snapshot.put("marginType", strategy.getMarginType() == null ? null : strategy.getMarginType().name());
        snapshot.put("feeRate", normBd(strategy.getFeeRate()));

        // 止损
        snapshot.put("stopLossPct", normBd(strategy.getStopLossPct()));
        snapshot.put("atrStopMultiplier", normBd(strategy.getAtrStopMultiplier()));
        snapshot.put("initialStopMultiplier", normBd(strategy.getInitialStopMultiplier()));
        snapshot.put("breakevenActivationMultiplier", normBd(strategy.getBreakevenActivationMultiplier()));
        snapshot.put("trailingActivationMultiplier", normBd(strategy.getTrailingActivationMultiplier()));
        snapshot.put("trailingDistanceMultiplier", normBd(strategy.getTrailingDistanceMultiplier()));
        snapshot.put("trailingDropPct", normBd(strategy.getTrailingDropPct()));
        snapshot.put("superTrendSlOffsetPct", normBd(strategy.getSuperTrendSlOffsetPct()));
        snapshot.put("exitOnOppositeVoteRatio", normBd(strategy.getExitOnOppositeVoteRatio()));
        snapshot.put("pauseOnStopLoss", strategy.getPauseOnStopLoss());

        // 止盈（单级 + 分阶段）
        snapshot.put("takeProfitPct", normBd(strategy.getTakeProfitPct()));
        snapshot.put("atrTakeProfitMultiplier", normBd(strategy.getAtrTakeProfitMultiplier()));
        snapshot.put("takeProfitPct1", normBd(strategy.getTakeProfitPct1()));
        snapshot.put("takeProfitSize1", normBd(strategy.getTakeProfitSize1()));
        snapshot.put("takeProfitPct2", normBd(strategy.getTakeProfitPct2()));
        snapshot.put("takeProfitSize2", normBd(strategy.getTakeProfitSize2()));
        snapshot.put("takeProfitPct3", normBd(strategy.getTakeProfitPct3()));
        snapshot.put("takeProfitSize3", normBd(strategy.getTakeProfitSize3()));
        snapshot.put("moveStopToBreakevenAfterStage", strategy.getMoveStopToBreakevenAfterStage());

        // ── 回测配置 ──────────────────────────────────────
        snapshot.put("startTime",      config.getStartTime());
        snapshot.put("endTime",        config.getEndTime());
        snapshot.put("cfgInitialCapital", normBd(config.getInitialCapital()));
        snapshot.put("cfgPositionRatio",  normBd(config.getPositionRatio()));
        snapshot.put("cfgFeeRate",        normBd(config.getFeeRate()));

        // TreeMap 已保证 key 排序；JSON.toJSONString 默认按 Map 迭代顺序输出 → 稳定一致。
        // 不需要 MapSortField（那是给非有序 Map 用的，本处会引入额外开销）。
        String json = JSON.toJSONString(snapshot);
        return sha256Hex(json);
    }

    /** BigDecimal → 规范化字符串，"2.0"/"2.00"/null 统一表达 */
    private static String normBd(BigDecimal v) {
        return v == null ? null : v.stripTrailingZeros().toPlainString();
    }

    /**
     * 把 JSON 字符串（indicatorConfigs 等）反序列化为 JSONArray，
     * 内部所有 Map 转 TreeMap 强制按 key 排序，再重新序列化消除顺序差异。
     * <p>纯标准 fastjson2 API，不依赖具体版本的 JSONWriter.Feature 枚举。</p>
     */
    private static String normalizeJsonArray(String jsonStr) {
        if (jsonStr == null || jsonStr.isBlank()) return null;
        try {
            JSONArray arr = JSON.parseArray(jsonStr);
            java.util.List<Object> normalized = new java.util.ArrayList<>(arr.size());
            for (Object item : arr) {
                normalized.add(deepSort(item));
            }
            return JSON.toJSONString(normalized);
        } catch (Exception e) {
            log.warn("[CacheKey] normalize JSON array failed, fall back to raw string: {}", e.getMessage());
            return jsonStr;
        }
    }

    /**
     * 递归把 Map 转 TreeMap、List 内部元素同样递归处理。
     * 保证嵌套结构的 key 顺序在序列化时完全一致。
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Object deepSort(Object node) {
        if (node instanceof java.util.Map) {
            TreeMap<String, Object> sorted = new TreeMap<>();
            for (Object e : ((java.util.Map) node).entrySet()) {
                java.util.Map.Entry entry = (java.util.Map.Entry) e;
                sorted.put(String.valueOf(entry.getKey()), deepSort(entry.getValue()));
            }
            return sorted;
        }
        if (node instanceof java.util.List) {
            java.util.List<Object> out = new java.util.ArrayList<>();
            for (Object item : (java.util.List) node) {
                out.add(deepSort(item));
            }
            return out;
        }
        return node;
    }

    private static String sha256Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            // SHA-256 不可能不存在；兜底用 String.hashCode
            return Integer.toHexString(s.hashCode());
        }
    }
}

package com.vertex.service.farb.regime;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.vertex.service.farb.entity.FarbPosition;
import com.vertex.service.farb.entity.FarbStrategy;
import com.vertex.service.farb.mapper.FarbPositionMapper;
import com.vertex.service.farb.service.FarbEventLogger;
import com.vertex.service.farb.service.FarbStrategyService;
import com.vertex.service.farb.signal.FarbDataCollector;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 资金费套利 - Regime 扫描器（Sprint 3 核心）。
 * <p>
 * 定期扫全市场 → 每 RUNNING 策略跑一遍 filter + scorer → 产出候选队列（内存态）。
 * PairExecutor（Sprint 4）会消费此队列。
 * </p>
 *
 * 扫描节奏：默认 3 min 一次。每次遍历所有 RUNNING 策略 × 该策略允许的 symbols。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RegimeScanner {

    private final FarbStrategyService strategyService;
    private final FarbDataCollector dataCollector;
    private final RegimeMetricsCalculator metricsCalc;
    private final RegimeFilter filter;
    private final CandidateScorer scorer;
    private final YieldEstimator yieldEstimator;
    private final FarbPositionMapper positionMapper;
    private final FarbEventLogger eventLogger;
    private final PredictionState predictionState;

    /** strategyId → 最近一轮扫描产出的候选（有序，score 降序）*/
    private final Map<Long, List<ArbCandidate>> latestCandidates = new HashMap<>();

    /** strategyId → 最近一轮扫描的时间戳 */
    private final Map<Long, Long> lastScanAt = new HashMap<>();

    /** 主扫描 loop：每 3 min 一次 */
    @Scheduled(fixedDelayString = "PT3M", initialDelayString = "PT1M")
    public void scanAll() {
        List<FarbStrategy> running = strategyService.listRunning();
        if (running.isEmpty()) {
            log.debug("[RegimeScanner] no RUNNING strategy, skip");
            return;
        }
        Set<String> universe = dataCollector.getUniverse();
        if (universe.isEmpty()) {
            log.debug("[RegimeScanner] universe empty, skip");
            return;
        }

        for (FarbStrategy s : running) {
            try {
                scanOne(s, universe);
            } catch (Exception ex) {
                log.warn("[RegimeScanner] scan strategy={} failed: {}", s.getId(), ex.getMessage(), ex);
                eventLogger.error(s.getId(), "SCAN_FAILED",
                        Map.of("error", ex.getMessage()));
            }
        }
    }

    /**
     * 对单策略扫描全市场候选。
     */
    void scanOne(FarbStrategy strategy, Set<String> universe) {
        long startTs = System.currentTimeMillis();
        Set<String> allowed = resolveAllowedSymbols(strategy, universe);

        int qualified = 0, rejected = 0;
        Map<String, Integer> rejectReasons = new HashMap<>();
        List<ArbCandidate> passed = new ArrayList<>();

        int lookback = strategy.getLookbackSettlements() != null ? strategy.getLookbackSettlements() : 6;
        double minFundingBps = strategy.getMinAvgFundingBps().doubleValue();

        for (String sym : allowed) {
            // v2：被 ReversalPredictor 软触发暂停的 symbol，跳过开新仓
            if (predictionState.isPaused(sym)) {
                rejected++;
                rejectReasons.merge("PAUSED_BY_PREDICTOR", 1, Integer::sum);
                continue;
            }
            RegimeMetrics m = metricsCalc.compute(sym, lookback, minFundingBps);
            FilterResult f = filter.check(strategy, m);
            if (!f.isQualified()) {
                rejected++;
                rejectReasons.merge(reasonKey(f.getRejectReason()), 1, Integer::sum);
                continue;
            }

            CandidateScorer.ScoredCandidate sc = scorer.score(strategy, m);

            // 预期收益 filter：min_expected_yield_pct
            int assumedHold = Math.max(24, (strategy.getExitMaxHoldHours() != null
                    ? strategy.getExitMaxHoldHours() : 168) * 6 / 10);   // 60% of max hold 作为默认预期
            YieldEstimator.YieldEstimate est = yieldEstimator.estimate(strategy, m, assumedHold);
            if (est.annualizedPct() < strategy.getMinExpectedYieldPct().doubleValue()) {
                rejected++;
                rejectReasons.merge("YIELD_LOW", 1, Integer::sum);
                continue;
            }

            passed.add(ArbCandidate.builder()
                    .symbol(sym)
                    .score(sc.totalScore())
                    .metrics(m)
                    .expectedNetYieldPct(est.netPct())
                    .expectedAnnualizedYieldPct(est.annualizedPct())
                    .reason(sc.reason())
                    .generatedAt(startTs)
                    .build());
            qualified++;
        }

        // 按 score 降序
        passed.sort(Comparator.comparingDouble(ArbCandidate::getScore).reversed());

        // 受 max_open_pairs 限制取 top K
        int currentlyOpen = countOpenPairs(strategy.getId());
        int slotsAvailable = Math.max(0,
                (strategy.getMaxOpenPairs() != null ? strategy.getMaxOpenPairs() : 8) - currentlyOpen);
        // 保留全部 qualified 供 UI 展示，只在消费队列时按 slotsAvailable 限制
        latestCandidates.put(strategy.getId(), passed);
        lastScanAt.put(strategy.getId(), startTs);

        long tookMs = System.currentTimeMillis() - startTs;
        log.info("[RegimeScanner] strategy={} scanned {}/{} qualified={} rejected={} slotsAvail={} tookMs={}",
                strategy.getId(), allowed.size(), universe.size(), qualified, rejected, slotsAvailable, tookMs);

        Map<String, Object> payload = new HashMap<>();
        payload.put("scanned", allowed.size());
        payload.put("qualified", qualified);
        payload.put("rejected", rejected);
        payload.put("rejectReasons", rejectReasons);
        payload.put("slotsAvailable", slotsAvailable);
        payload.put("topCandidates", passed.stream().limit(5)
                .map(c -> Map.of("symbol", c.getSymbol(),
                        "score", String.format("%.1f", c.getScore()),
                        "annualPct", String.format("%.1f", c.getExpectedAnnualizedYieldPct()),
                        "reason", c.getReason()))
                .toList());
        payload.put("tookMs", tookMs);
        eventLogger.info(strategy.getId(), "SCAN_TICK", payload);
    }

    // ─── 候选查询 API（供 PairExecutor / UI 消费）───────────────────────

    public List<ArbCandidate> getLatestCandidates(long strategyId) {
        return latestCandidates.getOrDefault(strategyId, List.of());
    }

    public Long getLastScanAt(long strategyId) {
        return lastScanAt.get(strategyId);
    }

    // ─── 辅助 ──────────────────────────────────────────────────────────

    /** 应用策略配置的 white/blacklist，返回本策略允许扫描的 symbol 集 */
    private Set<String> resolveAllowedSymbols(FarbStrategy strategy, Set<String> universe) {
        Set<String> result = new java.util.LinkedHashSet<>();
        // 白名单模式
        if (strategy.getScanAllMarket() == null || strategy.getScanAllMarket() == 0) {
            if (strategy.getSymbolWhitelist() == null || strategy.getSymbolWhitelist().isBlank()) {
                return result;   // 未开全市场且无 whitelist → 空集
            }
            try {
                List<String> wl = com.alibaba.fastjson2.JSON.parseArray(
                        strategy.getSymbolWhitelist(), String.class);
                for (String s : wl) if (universe.contains(s)) result.add(s);
            } catch (Exception ex) {
                log.warn("[RegimeScanner] parse whitelist failed strategy={}: {}",
                        strategy.getId(), ex.getMessage());
            }
        } else {
            result.addAll(universe);
        }
        // 黑名单
        if (strategy.getSymbolBlacklist() != null && !strategy.getSymbolBlacklist().isBlank()) {
            try {
                List<String> bl = com.alibaba.fastjson2.JSON.parseArray(
                        strategy.getSymbolBlacklist(), String.class);
                result.removeAll(bl);
            } catch (Exception ex) {
                log.warn("[RegimeScanner] parse blacklist failed strategy={}: {}",
                        strategy.getId(), ex.getMessage());
            }
        }
        return result;
    }

    private int countOpenPairs(long strategyId) {
        Long cnt = positionMapper.selectCount(new LambdaQueryWrapper<FarbPosition>()
                .eq(FarbPosition::getStrategyId, strategyId)
                .in(FarbPosition::getStatus, "PENDING_ENTRY", "OPEN", "CLOSING"));
        return cnt == null ? 0 : cnt.intValue();
    }

    /** 把 filter reject 原因归类（去掉数值细节，便于聚合统计）*/
    private String reasonKey(String raw) {
        if (raw == null) return "UNKNOWN";
        int paren = raw.indexOf('(');
        return paren > 0 ? raw.substring(0, paren) : raw;
    }
}

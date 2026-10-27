package com.vertex.service.farb.execution;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.vertex.service.farb.entity.FarbPosition;
import com.vertex.service.farb.entity.FarbStrategy;
import com.vertex.service.farb.mapper.FarbPositionMapper;
import com.vertex.service.farb.regime.ArbCandidate;
import com.vertex.service.farb.regime.RegimeScanner;
import com.vertex.service.farb.service.FarbEventLogger;
import com.vertex.service.farb.service.FarbStrategyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 资金费套利 - 入场消费器。
 * <p>
 * 定期从 {@link RegimeScanner} 拿最新候选队列，为每个 RUNNING 策略生成新的 pair。
 * 遵守 max_open_pairs / max_pairs_per_symbol 限制。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EntryConsumer {

    private final FarbStrategyService strategyService;
    private final RegimeScanner scanner;
    private final PairExecutor executor;
    private final FarbPositionMapper positionMapper;
    private final FarbEventLogger eventLogger;

    /** 每 1 分钟消费一次候选，与 Scanner 的 3min 周期错开，允许 2min 反应 */
    @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT90S")
    public void consume() {
        List<FarbStrategy> running = strategyService.listRunning();
        for (FarbStrategy s : running) {
            try {
                consumeForStrategy(s);
            } catch (Exception ex) {
                log.warn("[EntryConsumer] consume strategy={} failed: {}", s.getId(), ex.getMessage(), ex);
            }
        }
    }

    private void consumeForStrategy(FarbStrategy strategy) {
        List<ArbCandidate> candidates = scanner.getLatestCandidates(strategy.getId());
        if (candidates.isEmpty()) return;

        // 计算可用槽位
        int maxOpen = strategy.getMaxOpenPairs() != null ? strategy.getMaxOpenPairs() : 8;
        int maxPerSymbol = strategy.getMaxPairsPerSymbol() != null ? strategy.getMaxPairsPerSymbol() : 1;
        List<FarbPosition> active = positionMapper.selectList(new LambdaQueryWrapper<FarbPosition>()
                .eq(FarbPosition::getStrategyId, strategy.getId())
                .in(FarbPosition::getStatus, "PENDING_ENTRY", "OPEN", "CLOSING"));
        Set<String> occupiedSymbols = new HashSet<>();
        Map<String, Integer> perSymbolCount = new java.util.HashMap<>();
        for (var p : active) {
            occupiedSymbols.add(p.getSymbol());
            perSymbolCount.merge(p.getSymbol(), 1, Integer::sum);
        }
        int slotsFree = Math.max(0, maxOpen - active.size());
        if (slotsFree == 0) {
            log.debug("[EntryConsumer] strategy={} at max_open_pairs, skip", strategy.getId());
            return;
        }

        int opened = 0;
        for (ArbCandidate c : candidates) {
            if (opened >= slotsFree) break;
            String sym = c.getSymbol();
            int cur = perSymbolCount.getOrDefault(sym, 0);
            if (cur >= maxPerSymbol) continue;

            FarbPosition p = executor.openPair(strategy, c);
            if (p != null) {
                perSymbolCount.merge(sym, 1, Integer::sum);
                opened++;
            } else {
                eventLogger.info(strategy.getId(), "CANDIDATE_OPEN_FAILED",
                        Map.of("symbol", sym, "score", String.format("%.1f", c.getScore()),
                                "advice", "看 PAIR_OPEN_ROLLBACK 事件排查"));
            }
        }
        if (opened > 0) {
            log.info("[EntryConsumer] strategy={} opened={} slots pairs (slotsFree={} candidates={})",
                    strategy.getId(), opened, slotsFree, candidates.size());
        }
    }
}

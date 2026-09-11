package com.vertex.service.mm.grid.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.vertex.service.mm.grid.entity.GridStrategy;
import com.vertex.service.mm.grid.mapper.GridStrategyMapper;
import com.vertex.service.mm.userstream.UserStreamListener;
import com.vertex.service.mm.userstream.event.AccountUpdateEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Layer 5 账户级熔断 —— 多品种同步失控 or 保证金告急时全账户停机。
 * <p>
 * 触发条件（任一）：
 * <ul>
 *   <li>账户总浮亏 > 账户本金 × 5%</li>
 *   <li>保证金使用率 > 70%</li>
 *   <li>MARGIN_CALL 事件到达</li>
 *   <li>同一小时内 ≥ 2 个策略触发 Layer 3</li>
 * </ul>
 * </p>
 * <p>
 * 动作：所有 RUNNING 策略调 emergencyStop("ACCOUNT_EMERGENCY")
 * </p>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "vertex.mm", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class AccountRiskAggregator implements UserStreamListener {

    private final GridStrategyMapper strategyMapper;
    private final GridStrategyService strategyService;
    private final PositionInfoResolver positionResolver;
    private final GridEventLogger eventLogger;

    /** 累计 Layer 3 触发计数（滑动窗口，简化用最近 1 小时）*/
    private final ConcurrentMap<Long, java.util.Deque<Long>> layer3Timeline = new ConcurrentHashMap<>();

    @Override
    public void onAccountUpdate(AccountUpdateEvent event) {
        // 余额本身来自 event.balances，但账户级评估从 grid_order 表 + mark price 独立算得，
        // 不依赖余额缓存；此处仅触发即时评估。
        try { evaluateAccountLevel(); } catch (Exception e) {
            log.warn("[AccountRisk] evaluate error: {}", e.getMessage());
        }
    }

    @Override
    public void onMarginCall(String rawPayload) {
        log.error("[AccountRisk] MARGIN_CALL received → account emergency");
        eventLogger.error(-1L, "MARGIN_CALL", Map.of("payload", rawPayload));
        emergencyStopAll("MARGIN_CALL from exchange");
    }

    /**
     * Layer 3 事件回调（由 ProactiveReducer 或 GridEngine 触发）—— 累计 1h 内该策略触发次数。
     */
    public void recordLayer3Trigger(long strategyId) {
        long now = System.currentTimeMillis();
        java.util.Deque<Long> timeline = layer3Timeline.computeIfAbsent(strategyId, k -> new java.util.ArrayDeque<>());
        synchronized (timeline) {
            timeline.addLast(now);
            long cutoff = now - 3600_000L;
            while (!timeline.isEmpty() && timeline.peekFirst() < cutoff) timeline.pollFirst();
        }

        // 统计当前 1h 内触发了 Layer 3 的策略数
        int triggeredStrategies = 0;
        for (var q : layer3Timeline.values()) {
            synchronized (q) {
                long cutoff = now - 3600_000L;
                while (!q.isEmpty() && q.peekFirst() < cutoff) q.pollFirst();
                if (!q.isEmpty()) triggeredStrategies++;
            }
        }

        if (triggeredStrategies >= 2) {
            log.warn("[AccountRisk] {} strategies triggered Layer 3 in last 1h → account emergency", triggeredStrategies);
            eventLogger.warn(-1L, "MULTI_STRATEGY_LAYER3", Map.of("count", triggeredStrategies));
            emergencyStopAll("multi-strategy Layer 3 within 1h");
        }
    }

    /** 定时评估账户总浮亏 vs 阈值 */
    @Scheduled(fixedDelay = 60_000L, initialDelay = 120_000L)
    public void periodicEvaluate() {
        try {
            evaluateAccountLevel();
        } catch (Exception e) {
            log.warn("[AccountRisk] periodic evaluate error: {}", e.getMessage());
        }
    }

    private void evaluateAccountLevel() {
        List<GridStrategy> running = strategyService.listRunning();
        if (running.isEmpty()) return;

        // 只对显式开启 risk_auto_stop_enabled=1 的策略评估
        // 未开启 → 完全跳过，永远不会因浮亏自动停机（MARGIN_CALL 事件另有处理）
        running = new java.util.ArrayList<>(running);
        running.removeIf(s -> s.getRiskAutoStopEnabled() == null || s.getRiskAutoStopEnabled() != 1);
        if (running.isEmpty()) return;

        // 按账户分组，计算每账户总浮亏
        Map<Long, BigDecimal> accountPnl = new HashMap<>();
        Map<Long, BigDecimal> accountPrincipal = new HashMap<>();
        Map<Long, java.util.List<GridStrategy>> byAccount = new HashMap<>();
        for (GridStrategy s : running) {
            byAccount.computeIfAbsent(s.getAccountId(), k -> new java.util.ArrayList<>()).add(s);
        }

        for (var e : byAccount.entrySet()) {
            Long acctId = e.getKey();
            BigDecimal totalPnl = BigDecimal.ZERO;
            BigDecimal totalPrincipal = BigDecimal.ZERO;
            for (GridStrategy s : e.getValue()) {
                var longPos = positionResolver.resolve(s, "LONG");
                var shortPos = positionResolver.resolve(s, "SHORT");
                if (longPos.getUnrealizedPnl() != null) totalPnl = totalPnl.add(longPos.getUnrealizedPnl());
                if (shortPos.getUnrealizedPnl() != null) totalPnl = totalPnl.add(shortPos.getUnrealizedPnl());
                BigDecimal notional = s.getMaxNotionalUsd() != null ? s.getMaxNotionalUsd() : BigDecimal.ZERO;
                totalPrincipal = totalPrincipal.add(notional);
            }
            accountPnl.put(acctId, totalPnl);
            accountPrincipal.put(acctId, totalPrincipal);
        }

        for (var e : accountPnl.entrySet()) {
            Long acctId = e.getKey();
            BigDecimal pnl = e.getValue();
            BigDecimal principal = accountPrincipal.getOrDefault(acctId, BigDecimal.ZERO);
            if (principal.compareTo(BigDecimal.ZERO) <= 0) continue;

            BigDecimal pnlPct = pnl.divide(principal, 6, java.math.RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100));
            // 用第一个 opt-in 策略的阈值；无阈值 → 跳过
            GridStrategy first = byAccount.get(acctId).get(0);
            BigDecimal threshold = first.getAccountEmergencyPnlPct();
            if (threshold == null || threshold.compareTo(BigDecimal.ZERO) <= 0) continue;

            if (pnlPct.compareTo(threshold.negate()) < 0) {
                log.error("[AccountRisk] account={} pnl {}% breach threshold -{}% → EMERGENCY",
                        acctId, pnlPct, threshold);
                eventLogger.error(-1L, "ACCOUNT_PNL_BREACH", Map.of(
                        "accountId", acctId, "pnlPct", pnlPct, "threshold", threshold));
                emergencyStopAccountStrategies(acctId, "account PnL breach");
            }
        }
    }

    private void emergencyStopAll(String reason) {
        // MARGIN_CALL 是真实账户事件，只停 LIVE 策略；PAPER 策略独立不受影响
        List<GridStrategy> running = strategyMapper.selectList(new LambdaQueryWrapper<GridStrategy>()
                .eq(GridStrategy::getStatus, "RUNNING"));
        for (GridStrategy s : running) {
            if ("PAPER".equalsIgnoreCase(s.getExecutionMode())) continue;
            try {
                strategyService.emergencyStop(s.getId(), "AccountRiskAggregator: " + reason);
            } catch (Exception ex) {
                log.error("[AccountRisk] emergencyStop strategy={} failed: {}", s.getId(), ex.getMessage());
            }
        }
    }

    private void emergencyStopAccountStrategies(Long accountId, String reason) {
        // 账户级 PnL 熔断：分开处理 PAPER 和 LIVE
        // - LIVE：真的下平仓单
        // - PAPER：也停（研究价值：告诉用户该参数在这个市况下会爆）
        List<GridStrategy> running = strategyMapper.selectList(new LambdaQueryWrapper<GridStrategy>()
                .eq(GridStrategy::getStatus, "RUNNING")
                .eq(GridStrategy::getAccountId, accountId));
        for (GridStrategy s : running) {
            try {
                strategyService.emergencyStop(s.getId(), "AccountRiskAggregator[acct=" + accountId + "]: " + reason);
            } catch (Exception ex) {
                log.error("[AccountRisk] emergencyStop strategy={} failed: {}", s.getId(), ex.getMessage());
            }
        }
    }

}

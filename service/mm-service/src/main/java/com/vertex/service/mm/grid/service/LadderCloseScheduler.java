package com.vertex.service.mm.grid.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.vertex.service.mm.grid.entity.GridOrder;
import com.vertex.service.mm.grid.entity.GridStrategy;
import com.vertex.service.mm.grid.mapper.GridOrderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 梯队平仓触发器（Layer 2B）—— 由用户核心洞察落地。
 * <p>
 * 每 30 秒扫描所有 RUNNING 策略：
 * <ul>
 *   <li>若某侧累积仓位 &gt; window×50% <b>且</b> 当前 layer &ge; LAYER2 <b>且</b> 该侧未挂过梯队 → 挂梯队平仓</li>
 *   <li>若已挂梯队且 layer 恢复到 NORMAL 或该侧累积 &lt; 20% → 撤销梯队</li>
 * </ul>
 * </p>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "vertex.mm", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class LadderCloseScheduler {

    private final GridStrategyService strategyService;
    private final SlidingWindowManager slidingWindow;
    private final PositionInfoResolver positionResolver;
    private final LadderCloseManager ladderManager;
    private final OrderIntentExecutor executor;
    private final GridOrderMapper orderMapper;
    private final AccountCredentialResolver credentialResolver;
    private final GridEventLogger eventLogger;

    /** 已挂梯队的标记：strategyId+side → true */
    private final ConcurrentMap<String, Boolean> ladderActive = new ConcurrentHashMap<>();

    @Scheduled(fixedDelay = 30_000L, initialDelay = 60_000L)
    public void tickAll() {
        List<GridStrategy> running = strategyService.listRunning();
        for (GridStrategy s : running) {
            try {
                evaluateAndAct(s, "LONG");
                evaluateAndAct(s, "SHORT");
            } catch (Exception ex) {
                log.warn("[LadderScheduler] strategy={} error: {}", s.getId(), ex.getMessage());
            }
        }
    }

    private void evaluateAndAct(GridStrategy strategy, String positionSide) {
        GridState state = slidingWindow.getState(strategy.getId());
        if (state == null) return;

        String key = strategy.getId() + ":" + positionSide;
        String layer = state.getCurrentLayer().get();
        int windowSize = state.getWindowSizePerSide();

        // 计算持仓 + 判定
        var pos = positionResolver.resolve(strategy, positionSide);
        boolean ladderIsActive = Boolean.TRUE.equals(ladderActive.get(key));

        // 恢复条件：layer 回 NORMAL 或该侧累积 < 20%
        boolean shouldCancel = ladderIsActive
                && ("NORMAL".equals(layer) || "LAYER1".equals(layer)
                    || pos.isEmpty() || pos.getOpenOrdersCount() < windowSize * 0.2);
        if (shouldCancel) {
            cancelExistingLadder(strategy, positionSide);
            ladderActive.remove(key);
            return;
        }

        // 触发条件：LAYER2+ 且累积 > 50% window
        boolean shouldPlace = !ladderIsActive
                && ("LAYER2".equals(layer) || "LAYER3".equals(layer))
                && pos.getOpenOrdersCount() > windowSize * 0.5;

        if (!shouldPlace) return;

        var intents = ladderManager.computeLadderIntents(strategy, positionSide,
                pos.getTotalQty(), pos.getAvgCost(), pos.getMarkPrice());
        if (intents.isEmpty()) return;

        try {
            var creds = credentialResolver.resolveOrPaper(strategy.getAccountId(),
                    "PAPER".equalsIgnoreCase(strategy.getExecutionMode()));
            var summary = executor.execute(intents, creds.apiKey(), creds.apiSecret());
            ladderActive.put(key, true);
            Map<String, Object> payload = new HashMap<>();
            payload.put("side", positionSide);
            payload.put("ladderCount", intents.size());
            payload.put("avgCost", pos.getAvgCost());
            payload.put("markPrice", pos.getMarkPrice());
            payload.put("summary", summary.toString());
            eventLogger.info(strategy.getId(), "LADDER_PLACE", payload);
            log.info("[LadderScheduler] strategy={} side={} placed {} ladders, {}",
                    strategy.getId(), positionSide, intents.size(), summary);
        } catch (Exception ex) {
            log.error("[LadderScheduler] strategy={} side={} place error: {}",
                    strategy.getId(), positionSide, ex.getMessage(), ex);
        }
    }

    private void cancelExistingLadder(GridStrategy strategy, String positionSide) {
        List<GridOrder> ladders = orderMapper.selectList(new LambdaQueryWrapper<GridOrder>()
                .eq(GridOrder::getGridStrategyId, strategy.getId())
                .eq(GridOrder::getPositionSide, positionSide)
                .eq(GridOrder::getRole, "LADDER_CLOSE")
                .eq(GridOrder::getStatus, "LIVE"));
        if (ladders.isEmpty()) return;

        try {
            var creds = credentialResolver.resolveOrPaper(strategy.getAccountId(),
                    "PAPER".equalsIgnoreCase(strategy.getExecutionMode()));
            List<GridOrderIntent> intents = new java.util.ArrayList<>();
            for (GridOrder l : ladders) {
                if (l.getExchangeOrderId() == null) continue;
                try {
                    Long id = Long.parseLong(l.getExchangeOrderId());
                    intents.add(GridOrderIntent.cancel(strategy.getId(), strategy.getSymbol(),
                            id, l.getGridPrice(), positionSide));
                } catch (NumberFormatException ignored) {}
            }
            if (!intents.isEmpty()) {
                var summary = executor.execute(intents, creds.apiKey(), creds.apiSecret());
                Map<String, Object> payload = new HashMap<>();
                payload.put("side", positionSide);
                payload.put("cancelled", ladders.size());
                payload.put("summary", summary.toString());
                eventLogger.info(strategy.getId(), "LADDER_CANCEL", payload);
                log.info("[LadderScheduler] strategy={} side={} cancelled {} ladders",
                        strategy.getId(), positionSide, ladders.size());
            }
        } catch (Exception ex) {
            log.warn("[LadderScheduler] cancel error strategy={}: {}", strategy.getId(), ex.getMessage());
        }
    }
}

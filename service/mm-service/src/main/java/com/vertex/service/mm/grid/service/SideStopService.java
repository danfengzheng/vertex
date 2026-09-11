package com.vertex.service.mm.grid.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.vertex.service.mm.grid.entity.GridStrategy;
import com.vertex.service.mm.grid.mapper.GridStrategyMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/**
 * Layer 4 分侧停机器 —— 单侧全撤 + 全平。
 * <p>
 * 触发：4h ADX > 35 且 该侧浮亏 > 3%
 * </p>
 * <p>
 * 动作：
 * <ol>
 *   <li>SlidingWindowManager.pauseSide → 撤该侧所有 open 单</li>
 *   <li>ProactiveReducer 触发 100% 减仓（一次性平掉，允许 taker fallback）</li>
 *   <li>Strategy.long_side_enabled / short_side_enabled 置 0</li>
 *   <li>另一侧保留</li>
 * </ol>
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SideStopService {

    private final GridStrategyMapper strategyMapper;
    private final SlidingWindowManager slidingWindow;
    private final OrderIntentExecutor executor;
    private final ProactiveReducer reducer;
    private final AccountCredentialResolver credentialResolver;
    private final GridEventLogger eventLogger;

    /**
     * 停止某侧。
     *
     * @param strategy    策略
     * @param positionSide LONG / SHORT
     * @param reason      理由（日志用）
     */
    public void stopSide(GridStrategy strategy, String positionSide, String reason) {
        log.warn("[SideStop] strategy={} side={} STOP triggered: {}",
                strategy.getId(), positionSide, reason);

        Map<String, Object> startPayload = new HashMap<>();
        startPayload.put("side", positionSide);
        startPayload.put("reason", reason);
        eventLogger.warn(strategy.getId(), "LAYER4_SIDE_STOP", startPayload);

        // ── Step 1: 撤该侧所有挂单 ──────────────
        var intents = slidingWindow.pauseSide(strategy, positionSide);
        if (!intents.isEmpty()) {
            try {
                var creds = credentialResolver.resolveOrPaper(strategy.getAccountId(),
                        "PAPER".equalsIgnoreCase(strategy.getExecutionMode()));
                executor.execute(intents, creds.apiKey(), creds.apiSecret());
            } catch (Exception ex) {
                log.warn("[SideStop] cancel intents error strategy={}: {}", strategy.getId(), ex.getMessage());
            }
        }

        // ── Step 2: 100% 减仓（一次性平掉该侧全部）─
        reducer.reduce(strategy, positionSide, new BigDecimal("100"));

        // ── Step 3: 更新 DB 状态 ────────────────
        try {
            LambdaUpdateWrapper<GridStrategy> w = new LambdaUpdateWrapper<>();
            w.eq(GridStrategy::getId, strategy.getId());
            if ("LONG".equalsIgnoreCase(positionSide)) {
                w.set(GridStrategy::getLongSideEnabled, 0);
            } else {
                w.set(GridStrategy::getShortSideEnabled, 0);
            }
            strategyMapper.update(null, w);
        } catch (Exception ignored) {}

        log.info("[SideStop] strategy={} side={} STOP complete", strategy.getId(), positionSide);
    }

    /**
     * 人工恢复某侧（Layer 4 触发后仅人工恢复）。
     */
    public void enableSide(long strategyId, String positionSide, Long operatorId) {
        LambdaUpdateWrapper<GridStrategy> w = new LambdaUpdateWrapper<>();
        w.eq(GridStrategy::getId, strategyId);
        if ("LONG".equalsIgnoreCase(positionSide)) {
            w.set(GridStrategy::getLongSideEnabled, 1);
        } else {
            w.set(GridStrategy::getShortSideEnabled, 1);
        }
        strategyMapper.update(null, w);
        slidingWindow.resumeSide(strategyId, positionSide);
        eventLogger.info(strategyId, "SIDE_RESUME", Map.of("side", positionSide, "operator", operatorId == null ? "unknown" : operatorId));
    }
}

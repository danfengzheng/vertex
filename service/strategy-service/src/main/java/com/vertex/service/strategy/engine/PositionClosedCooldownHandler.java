package com.vertex.service.strategy.engine;

import com.vertex.model.entity.quote.KLineInterval;
import com.vertex.model.entity.strategy.Strategy;
import com.vertex.model.event.PositionClosedEvent;
import com.vertex.service.strategy.mapper.StrategyMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 监听 order-service 发出的 PositionClosedEvent，
 * 把"出场 K 线的 openTime"写入 StrategyCooldownState，启动本策略的冷静期。
 * <p>
 * 冷静期边界：以当前时间所在 K 线的 openTime 作为"出场 bar openTime"。
 * 下一根 K 收盘触发的信号会跨越该 openTime，自动放行。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PositionClosedCooldownHandler {

    private final StrategyMapper strategyMapper;
    private final StrategyCooldownState cooldownState;

    @EventListener
    public void onPositionClosed(PositionClosedEvent event) {
        if (event.getStrategyId() == null) return;
        try {
            Strategy strategy = strategyMapper.selectById(event.getStrategyId());
            if (strategy == null || strategy.getInterval() == null) return;
            KLineInterval interval = strategy.getInterval();
            long intervalMs = interval.getMillis();
            if (intervalMs <= 0) return;
            // 出场 K 线的 openTime = floor(closedAt / intervalMs) × intervalMs
            long exitBarOpenTime = (event.getClosedAtMs() / intervalMs) * intervalMs;
            cooldownState.recordExitBarOpenTime(strategy.getId(), exitBarOpenTime);
            log.info("[Cooldown] Position closed → strategy={} interval={} exitBarOpenTime={} reason={}",
                    strategy.getName(), interval.getCode(), exitBarOpenTime, event.getExitReason());
        } catch (Exception ex) {
            log.warn("[Cooldown] Failed to record exit for strategy={}: {}",
                    event.getStrategyId(), ex.getMessage());
        }
    }
}

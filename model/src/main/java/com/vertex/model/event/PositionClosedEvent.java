package com.vertex.model.event;

import lombok.Getter;

/**
 * 持仓关闭事件（Spring ApplicationEvent）
 * <p>
 * 由 order-service 在平仓完成后发布；strategy-service 的 StrategyCooldownState 监听，
 * 记录"出场 K 线的 openTime"用于后续信号冷静期判定。
 * </p>
 * <p>
 * 使用 Spring 事件而不是新增接口：避免为单一场景引入双向依赖。
 * </p>
 */
@Getter
public class PositionClosedEvent {

    /** 策略 id（null 表示非策略持仓，监听者应忽略） */
    private final Long strategyId;

    /** 平仓完成时的墙上时间（毫秒）。用于反推"出场 K 线的 openTime" */
    private final long closedAtMs;

    /** 出场原因（SL/TP/SIGNAL/TRAILING/STAGED_TP/...），仅日志用 */
    private final String exitReason;

    public PositionClosedEvent(Long strategyId, long closedAtMs, String exitReason) {
        this.strategyId = strategyId;
        this.closedAtMs = closedAtMs;
        this.exitReason = exitReason;
    }
}

package com.vertex.service.farb.regime;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 预测反转 - 共享暂停态。
 * <p>
 * v2：软阈值触发时，Exit 引擎会把 symbol 挂到这里；Scanner 在开新仓时先检查。
 * 记录的是 pauseUntilMs，过期自动失效。
 * </p>
 */
@Slf4j
@Component
public class PredictionState {

    private final ConcurrentHashMap<String, Long> pauseUntil = new ConcurrentHashMap<>();

    /** 暂停 symbol 开新仓 durationMs（若已有更晚过期时间则保留晚的） */
    public void pauseNewOpens(String symbol, long durationMs) {
        long until = System.currentTimeMillis() + durationMs;
        pauseUntil.merge(symbol, until, Math::max);
        log.debug("[PredictionState] pause {} until {}", symbol, until);
    }

    /** 该 symbol 是否处于暂停开新仓状态 */
    public boolean isPaused(String symbol) {
        Long until = pauseUntil.get(symbol);
        if (until == null) return false;
        if (System.currentTimeMillis() >= until) {
            pauseUntil.remove(symbol);
            return false;
        }
        return true;
    }

    /** 手动清除（比如策略停止时） */
    public void clear(String symbol) {
        pauseUntil.remove(symbol);
    }

    public int pausedCount() {
        long now = System.currentTimeMillis();
        return (int) pauseUntil.values().stream().filter(v -> v > now).count();
    }
}

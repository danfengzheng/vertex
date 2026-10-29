package com.vertex.service.strategy.engine;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 策略冷静期状态（内存态）
 * <p>
 * 用于实盘和回测对齐：
 * <ul>
 *   <li>回测：exit 发生在 bar N → bar N 的 close 信号被屏蔽（closedByStopOrTp 开关）</li>
 *   <li>实盘：exit 发生在 bar N 的中段（SL/TP mid-bar 触发）→ 不该在 bar N close 时还开新仓</li>
 * </ul>
 * 本冷静期以"K close 时间起算"：exit 发生于 bar N 时，记录 bar N 的 openTime 作为冷静边界。
 * 后续信号判定时取"当前所在 bar 的 openTime"，如果仍 ≤ exitBarOpenTime，则在冷静期内，屏蔽本次交易执行。
 * <p>
 * 冷静期自然过期（下一根 K 收盘时 currentBarOpen > exitBarOpenTime，自动放行）；
 * 过期时 lazy remove，避免 map 无限增长。
 * </p>
 * <p>
 * 多策略隔离：按 strategyId 独立维护；同一 symbol 的不同策略互不干扰。
 * </p>
 * <p>
 * 重启不持久化：丢状态等价于"冷静期立即过期"，影响极小（最多多做一笔不该做的交易）。
 * 如果需要持久化，接 Redis 带 TTL 即可，无需改接口。
 * </p>
 */
@Slf4j
@Component
public class StrategyCooldownState {

    /** strategyId → 最近一次 exit 发生所在 K 线的 openTime（毫秒） */
    private final ConcurrentHashMap<Long, Long> exitBarOpenTimeMap = new ConcurrentHashMap<>();

    /**
     * 记录出场 K 线的 openTime。
     * @param strategyId 策略 id
     * @param exitBarOpenTime 出场发生所在 K 线的 openTime（毫秒）
     */
    public void recordExitBarOpenTime(long strategyId, long exitBarOpenTime) {
        // 用 max 避免乱序事件把更新的 exit 覆盖成老的
        Long prev = exitBarOpenTimeMap.merge(strategyId, exitBarOpenTime, Math::max);
        log.debug("[Cooldown] record exit strategy={} exitBarOpenTime={} (prev={})",
                strategyId, exitBarOpenTime, prev);
    }

    /**
     * 判断当前是否在冷静期内。
     * @param strategyId 策略 id
     * @param currentBarOpenTime 当前信号所在 K 线的 openTime（毫秒）
     * @return true=仍在冷静期（本次信号应屏蔽交易执行）
     */
    public boolean inCooldown(long strategyId, long currentBarOpenTime) {
        Long exitBarOpenTime = exitBarOpenTimeMap.get(strategyId);
        if (exitBarOpenTime == null) return false;
        if (currentBarOpenTime > exitBarOpenTime) {
            // 已跨到下一根 K，冷静期自然过期，lazy 清理
            exitBarOpenTimeMap.remove(strategyId, exitBarOpenTime);
            return false;
        }
        return true;
    }

    /** 手动清除（用于测试或策略重启场景） */
    public void clear(long strategyId) {
        exitBarOpenTimeMap.remove(strategyId);
    }

    public int size() {
        return exitBarOpenTimeMap.size();
    }
}

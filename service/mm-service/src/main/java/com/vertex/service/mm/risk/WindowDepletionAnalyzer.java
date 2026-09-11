package com.vertex.service.mm.risk;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 窗口耗尽率分析器 —— 用户的核心洞察：
 * <p>
 * <b>"网格挂单被吃光" 是比 ADX 更快的实时趋势判据</b>。
 * 事件驱动：GridEngine 收到成交立即 push 到这里。
 * 定时（每分钟）汇总计算各种衍生指标。
 * </p>
 * <p>
 * 单实例服务，通过 strategyId 分区（每个策略独立状态）。
 * </p>
 */
@Slf4j
@Component
public class WindowDepletionAnalyzer {

    private static final int MAX_FILL_HISTORY = 200;      // 每策略保留最近 200 次成交
    private static final long WINDOW_3MIN_MS  = 3L * 60_000L;
    private static final long WINDOW_30MIN_MS = 30L * 60_000L;

    private final ConcurrentMap<Long, StrategyState> states = new ConcurrentHashMap<>();

    /**
     * 记录一次开仓成交。
     * @param strategyId 策略 ID
     * @param positionSide LONG / SHORT（区分是哪一侧被吃）
     * @param timeMs 成交时间戳
     */
    public void onOpenFill(long strategyId, String positionSide, long timeMs) {
        StrategyState state = states.computeIfAbsent(strategyId, k -> new StrategyState());
        synchronized (state) {
            // 记录事件
            if (state.fills.size() >= MAX_FILL_HISTORY) state.fills.pollFirst();
            state.fills.addLast(new Fill(positionSide, timeMs));

            // 更新连续同侧计数
            if (positionSide.equalsIgnoreCase(state.lastConsecutiveSide)) {
                state.lastConsecutiveCount++;
            } else {
                state.lastConsecutiveSide = positionSide;
                state.lastConsecutiveCount = 1;
            }

            // 更新距上次反向时间
            state.lastFillSide = positionSide;
            state.lastFillTimeMs = timeMs;
        }
    }

    /**
     * 收到反向成交时（平仓单成交）—— 意味着有反弹发生，重置计时。
     */
    public void onCloseFill(long strategyId, String positionSide, long timeMs) {
        StrategyState state = states.computeIfAbsent(strategyId, k -> new StrategyState());
        synchronized (state) {
            state.lastReverseFillTimeMs = timeMs;
        }
    }

    /**
     * 计算当前状态（可能被高频调用，尽量 O(N) 其中 N ≤ 200）。
     *
     * @param strategyId 策略
     * @param longSideOpenOrders  当前 LONG 侧活跃的开仓单数（未成交）
     * @param shortSideOpenOrders 当前 SHORT 侧活跃的开仓单数
     * @param windowSizePerSide 每侧总容量
     * @return 当前状态快照
     */
    public DepletionState compute(long strategyId,
                                   int longSideOpenOrders,
                                   int shortSideOpenOrders,
                                   int windowSizePerSide) {
        StrategyState state = states.get(strategyId);
        long now = System.currentTimeMillis();

        // Depletion = 该侧 OPEN 单实际成交数 / 窗口容量。
        // 不能用 "windowSize - 活跃单数" 作为分子，那会把 GTX 拒单当成 fill（拒单从未挂上，市场没吃它）。
        int longFillsSinceReshuffle = 0;
        int shortFillsSinceReshuffle = 0;
        if (state != null) {
            long since = state.lastReshuffleAtMs;
            synchronized (state) {
                longFillsSinceReshuffle = countFillsSince(state, "LONG", since);
                shortFillsSinceReshuffle = countFillsSince(state, "SHORT", since);
            }
        }
        double longDepletion  = pct(longFillsSinceReshuffle,  windowSizePerSide);
        double shortDepletion = pct(shortFillsSinceReshuffle, windowSizePerSide);

        if (state == null) {
            return DepletionState.builder()
                    .strategyId(strategyId)
                    .longDepletionPct(longDepletion)
                    .shortDepletionPct(shortDepletion)
                    .asymmetryRatio(1.0)
                    .consecutiveSameSide(0)
                    .secondsSinceLastReverseFill(0)
                    .build();
        }

        synchronized (state) {
            int longFills3min  = countFillsSince(state, "LONG",  now - WINDOW_3MIN_MS);
            int shortFills3min = countFillsSince(state, "SHORT", now - WINDOW_3MIN_MS);
            int longFills30min = countFillsSince(state, "LONG",  now - WINDOW_30MIN_MS);
            int shortFills30min = countFillsSince(state, "SHORT", now - WINDOW_30MIN_MS);

            double longSpeed3 = longFills3min / 3.0;
            double shortSpeed3 = shortFills3min / 3.0;
            double longSpeed30 = longFills30min / 30.0;
            double shortSpeed30 = shortFills30min / 30.0;

            // 不对称率（用 30min 窗口更稳）
            double asymmetry;
            if (shortSpeed30 == 0 && longSpeed30 == 0) asymmetry = 1.0;
            else if (shortSpeed30 == 0) asymmetry = 100.0;
            else asymmetry = longSpeed30 / shortSpeed30;

            long secondsSinceReverse = state.lastReverseFillTimeMs == 0
                    ? 0 : (now - state.lastReverseFillTimeMs) / 1000L;

            return DepletionState.builder()
                    .strategyId(strategyId)
                    .longDepletionPct(longDepletion)
                    .shortDepletionPct(shortDepletion)
                    .longSpeedPer3Min(longSpeed3)
                    .shortSpeedPer3Min(shortSpeed3)
                    .longSpeedPer30Min(longSpeed30)
                    .shortSpeedPer30Min(shortSpeed30)
                    .asymmetryRatio(asymmetry)
                    .consecutiveSameSide(state.lastConsecutiveCount)
                    .consecutiveSide(state.lastConsecutiveSide)
                    .secondsSinceLastReverseFill(secondsSinceReverse)
                    .build();
        }
    }

    /** 策略停止/删除时调 */
    public void reset(long strategyId) {
        states.remove(strategyId);
    }

    /**
     * shift/reshuffle 时调用 —— 重置 depletion 基线，之前的 fill 不再计入当前窗口的耗尽度。
     * 保留 fills 记录（用于 speed / asymmetry 等衍生指标依然要看最近历史）。
     */
    public void onReshuffle(long strategyId) {
        StrategyState state = states.get(strategyId);
        if (state != null) {
            state.lastReshuffleAtMs = System.currentTimeMillis();
        }
    }

    // ─── 内部 ────────────────────────────────────────────────

    private static int countFillsSince(StrategyState state, String side, long sinceMs) {
        int n = 0;
        for (Fill f : state.fills) {
            if (f.timeMs >= sinceMs && side.equalsIgnoreCase(f.side)) n++;
        }
        return n;
    }

    private static double pct(int filled, int total) {
        if (total <= 0) return 0;
        return Math.max(0, Math.min(100, (filled * 100.0) / total));
    }

    private static class StrategyState {
        final Deque<Fill> fills = new ArrayDeque<>(MAX_FILL_HISTORY);
        /** 上次 reshuffle 时间戳；depletion 只统计此之后的 fill */
        volatile long lastReshuffleAtMs = System.currentTimeMillis();
        String lastConsecutiveSide = "";
        int lastConsecutiveCount = 0;
        String lastFillSide;
        long lastFillTimeMs;
        long lastReverseFillTimeMs;
    }

    private static class Fill {
        final String side;
        final long timeMs;
        Fill(String side, long timeMs) {
            this.side = side;
            this.timeMs = timeMs;
        }
    }
}

package com.vertex.service.mm.risk;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 窗口耗尽状态快照。
 * <p>
 * 每次成交事件更新，或定时（每分钟）计算。
 * TrendScoreEvaluator 综合此 state 打分。
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DepletionState {

    private long strategyId;

    // ─── 单侧耗尽率（Layer 2/3 判据）──────────────────
    /** 已成交的 LONG BUY 单数 / window_size_per_side */
    private double longDepletionPct;
    /** 已成交的 SHORT SELL 单数 / window_size_per_side */
    private double shortDepletionPct;

    // ─── 速率（Layer 1 早预警）────────────────────────
    /** 最近 3 分钟每分钟 LONG 侧成交速率 */
    private double longSpeedPer3Min;
    /** 最近 3 分钟每分钟 SHORT 侧成交速率 */
    private double shortSpeedPer3Min;

    /** 最近 30 分钟每分钟 LONG 速率 */
    private double longSpeedPer30Min;
    private double shortSpeedPer30Min;

    // ─── 不对称率（跨 window_size 可比）────────────────
    /** = long_speed / short_speed。 > 3 or < 0.33 视为趋势 */
    private double asymmetryRatio;

    // ─── 连续同侧成交（最直觉的信号）──────────────────
    /** 连续 N 次同侧成交 */
    private int consecutiveSameSide;
    /** 连续方向："LONG" or "SHORT" */
    private String consecutiveSide;

    // ─── 反弹间隔（长时间无反向 = 无反弹机会）──────────
    /** 距上次反向成交的秒数 */
    private long secondsSinceLastReverseFill;

    // ─── 综合评分 ─────────────────────────────────────
    /** 0-10 综合评分 */
    private int trendScore;

    /** 各维度打分明细（前端展示用） */
    private ScoreBreakdown breakdown;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ScoreBreakdown {
        private int adxScore;
        private int depletionScore;
        private int asymmetryScore;
        private int consecutiveScore;
        private int reverseGapScore;
    }
}

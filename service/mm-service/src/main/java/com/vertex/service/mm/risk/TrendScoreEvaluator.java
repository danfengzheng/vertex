package com.vertex.service.mm.risk;

import com.vertex.service.mm.grid.entity.GridStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 综合趋势评分器 —— 多信号打分制。
 * <p>
 * 5 维打分：ADX / Depletion / Asymmetry / Consecutive / ReverseGap
 * 总分 0-10，映射到 5 层保护。
 * </p>
 * <p>
 * 骨架版本：目前 ADX 传入 0 表示未提供，只算其他 4 维。
 * ADX 由调用方（AdaptiveScheduler / TrendIndicatorCache）从 IndicatorRegistry 计算后注入。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TrendScoreEvaluator {

    /**
     * 综合评分。
     * @param strategy 策略配置（读权重 + 阈值）
     * @param depletion 从 WindowDepletionAnalyzer 拿到的状态
     * @param adx 当前 ADX 值；null 或 <=0 表示未计算
     * @return 完整评分 + breakdown
     */
    public DepletionState evaluate(GridStrategy strategy, DepletionState depletion, BigDecimal adx) {
        int adxScore         = scoreAdx(strategy, adx);
        int depletionScore   = scoreDepletion(strategy, depletion);
        int asymmetryScore   = scoreAsymmetry(strategy, depletion);
        int consecutiveScore = scoreConsecutive(strategy, depletion);
        int reverseGapScore  = scoreReverseGap(strategy, depletion);

        int total = adxScore + depletionScore + asymmetryScore + consecutiveScore + reverseGapScore;

        depletion.setTrendScore(total);
        depletion.setBreakdown(DepletionState.ScoreBreakdown.builder()
                .adxScore(adxScore)
                .depletionScore(depletionScore)
                .asymmetryScore(asymmetryScore)
                .consecutiveScore(consecutiveScore)
                .reverseGapScore(reverseGapScore)
                .build());
        return depletion;
    }

    /**
     * 分数 → 应触发的保护层级。
     * @return "NORMAL" / "LAYER1" / "LAYER2" / "LAYER3" / "LAYER4"
     */
    public String mapScoreToLayer(GridStrategy strategy, int score) {
        int l1 = orDefault(strategy.getScoreLayer1Min(), 3);
        int l2 = orDefault(strategy.getScoreLayer2Min(), 5);
        int l3 = orDefault(strategy.getScoreLayer3Min(), 7);
        int l4 = orDefault(strategy.getScoreLayer4Min(), 9);
        if (score >= l4) return "LAYER4";
        if (score >= l3) return "LAYER3";
        if (score >= l2) return "LAYER2";
        if (score >= l1) return "LAYER1";
        return "NORMAL";
    }

    // ─── 5 维打分（每维 0-max 分）─────────────────────────────

    private int scoreAdx(GridStrategy s, BigDecimal adx) {
        int max = orDefault(s.getScoreWeightAdx(), 3);
        if (adx == null || adx.compareTo(BigDecimal.ZERO) <= 0) return 0;
        double v = adx.doubleValue();
        if (v >= orDouble(s.getTrendLayer4AdxEnter(), 35)) return max;
        if (v >= orDouble(s.getTrendLayer3AdxEnter(), 30)) return Math.max(1, max - 1);
        if (v >= orDouble(s.getTrendLayer2AdxEnter(), 25)) return Math.max(1, max - 2);
        if (v >= 20) return 1;
        return 0;
    }

    private int scoreDepletion(GridStrategy s, DepletionState d) {
        int max = orDefault(s.getScoreWeightDepletion(), 3);
        double worst = Math.max(d.getLongDepletionPct(), d.getShortDepletionPct());
        double t2 = orDouble(s.getDepletionPctLayer2(), 60);
        double t3 = orDouble(s.getDepletionPctLayer3(), 100);
        if (worst >= t3) return max;
        if (worst >= t2) return Math.max(1, max - 1);
        if (worst >= 40) return 1;
        return 0;
    }

    private int scoreAsymmetry(GridStrategy s, DepletionState d) {
        int max = orDefault(s.getScoreWeightAsymmetry(), 2);
        double r = d.getAsymmetryRatio();
        double inv = r == 0 ? 100 : 1.0 / r;
        double dev = Math.max(r, inv);   // 偏离对称的最大方向
        if (dev >= 10) return max;
        if (dev >= 5)  return Math.max(1, max - 1);
        if (dev >= 3)  return 1;
        return 0;
    }

    private int scoreConsecutive(GridStrategy s, DepletionState d) {
        int max = orDefault(s.getScoreWeightConsecutive(), 1);
        int c = d.getConsecutiveSameSide();
        if (c >= 15) return max;
        if (c >= 10) return Math.max(0, max);
        if (c >= 5)  return 0;
        return 0;
    }

    private int scoreReverseGap(GridStrategy s, DepletionState d) {
        int max = orDefault(s.getScoreWeightReverseGap(), 1);
        long g = d.getSecondsSinceLastReverseFill();
        if (g == 0) return 0;
        if (g >= 3600) return max;      // 60 分钟无反向
        if (g >= 1800) return 0;
        return 0;
    }

    // ─── 助手 ────────────────────────────────────────────────

    private static int orDefault(Integer v, int fallback) {
        return v == null ? fallback : v;
    }
    private static double orDouble(BigDecimal v, double fallback) {
        return v == null ? fallback : v.doubleValue();
    }
}

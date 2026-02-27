package com.vertex.service.chain.scorer;

import lombok.Builder;
import lombok.Data;

/**
 * 代币评分结果
 */
@Data
@Builder
public class TokenScoreResult {

    /** 总分（0-100） */
    private int totalScore;

    /** 链上指标得分（0-30） */
    private int scoreOnChain;

    /** 市场指标得分（0-40） */
    private int scoreMarket;

    /** 代币经济得分（0-20） */
    private int scoreTokenomics;

    /** 新颖度得分（0-10） */
    private int scoreNovelty;

    /** 评级：S≥80 / A≥65 / B≥50 / C≥35 / D<35 */
    private String grade;

    /**
     * 根据总分计算评级
     */
    public static String calcGrade(int totalScore) {
        if (totalScore >= 80) return "S";
        if (totalScore >= 65) return "A";
        if (totalScore >= 50) return "B";
        if (totalScore >= 35) return "C";
        return "D";
    }

    /**
     * 汇总各维度分数并生成结果
     */
    public static TokenScoreResult of(int onChain, int market, int tokenomics, int novelty) {
        int total = onChain + market + tokenomics + novelty;
        return TokenScoreResult.builder()
                .scoreOnChain(clamp(onChain, 0, ScoreDimension.ON_CHAIN.getMaxScore()))
                .scoreMarket(clamp(market, 0, ScoreDimension.MARKET.getMaxScore()))
                .scoreTokenomics(clamp(tokenomics, 0, ScoreDimension.TOKENOMICS.getMaxScore()))
                .scoreNovelty(clamp(novelty, 0, ScoreDimension.NOVELTY.getMaxScore()))
                .totalScore(clamp(total, 0, 100))
                .grade(calcGrade(total))
                .build();
    }

    private static int clamp(int val, int min, int max) {
        return Math.max(min, Math.min(max, val));
    }
}

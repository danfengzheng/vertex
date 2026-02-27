package com.vertex.service.chain.scorer.impl;

import com.vertex.service.chain.scorer.ScoreDimension;
import com.vertex.service.chain.scorer.TokenScorer;
import com.vertex.service.chain.source.NewTokenRawData;
import org.springframework.stereotype.Component;

/**
 * 新颖度评分器（满分 10 分）
 *
 * <pre>
 * 子项               满分  评分逻辑
 * 存在时长           5     ≤15min→5（极新）, ≤30min→4, ≤60min→3, ≤120min→2, ≤360min→1, else 0
 * Pump.fun 上市      3     pumpFunListed→3, else 0
 * LP 添加次数        2     ≥3→2, ≥2→1, else 0
 * </pre>
 */
@Component
public class NoveltyScorer implements TokenScorer {

    @Override
    public ScoreDimension dimension() {
        return ScoreDimension.NOVELTY;
    }

    @Override
    public int score(NewTokenRawData data) {
        int score = 0;

        // 存在时长（5分，越新越高分）
        int age = data.getAgeMinutes() != null ? data.getAgeMinutes() : Integer.MAX_VALUE;
        if (age <= 15) score += 5;
        else if (age <= 30) score += 4;
        else if (age <= 60) score += 3;
        else if (age <= 120) score += 2;
        else if (age <= 360) score += 1;

        // Pump.fun 上市标记（3分）
        if (Boolean.TRUE.equals(data.getPumpFunListed())) score += 3;

        // LP 添加次数（2分）
        int lpCount = data.getLpAddCount() != null ? data.getLpAddCount() : 0;
        if (lpCount >= 3) score += 2;
        else if (lpCount >= 2) score += 1;

        return Math.min(score, dimension().getMaxScore());
    }
}

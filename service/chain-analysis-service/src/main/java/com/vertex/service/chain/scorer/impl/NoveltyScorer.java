package com.vertex.service.chain.scorer.impl;

import com.vertex.service.chain.scorer.ScoreDimension;
import com.vertex.service.chain.scorer.TokenScorer;
import com.vertex.service.chain.source.NewTokenRawData;
import org.springframework.stereotype.Component;

/**
 * 新颖度评分器（满分 10 分）
 * <p>
 * 衡量代币的"早期发现价值"：越新、社区越活跃、越接近毕业的代币得分越高。
 *
 * <pre>
 * 子项               满分  评分逻辑
 * 存在时长           5     ≤15min→5（极新）, ≤30min→4, ≤60min→3, ≤120min→2, ≤360min→1, else 0
 * 一级市场信号       3     bondingCurveProgress≥70%→3, ≥30%→2, pumpFunListed=true→1, else 0
 * 社区热度           2     replyCount≥50→2, ≥10→1, else 0
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
        if (age <= 15)       score += 5;
        else if (age <= 30)  score += 4;
        else if (age <= 60)  score += 3;
        else if (age <= 120) score += 2;
        else if (age <= 360) score += 1;

        // 一级市场信号（3分）
        Double bcp = data.getBondingCurveProgress();
        if (bcp != null && bcp >= 70) {
            score += 3; // bonding curve 接近毕业，最强早期机会信号
        } else if (bcp != null && bcp >= 30) {
            score += 2;
        } else if (Boolean.TRUE.equals(data.getPumpFunListed())) {
            score += 1; // 至少在 pump.fun/launchpad 上市
        }

        // 社区热度（2分）
        int rc = data.getReplyCount() != null ? data.getReplyCount() : 0;
        if (rc >= 50)      score += 2;
        else if (rc >= 10) score += 1;

        return Math.min(score, dimension().getMaxScore());
    }
}

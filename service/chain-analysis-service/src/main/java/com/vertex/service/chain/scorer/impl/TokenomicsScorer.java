package com.vertex.service.chain.scorer.impl;

import com.vertex.service.chain.scorer.ScoreDimension;
import com.vertex.service.chain.scorer.TokenScorer;
import com.vertex.service.chain.source.NewTokenRawData;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 代币经济评分器（满分 20 分）
 *
 * <pre>
 * 子项               满分  评分逻辑（越分散越好）
 * Top10 持有者集中度  10   ≤20%→10, ≤30%→7, ≤40%→5, ≤50%→3, ≤70%→1, else 0
 * 部署者持仓占比      6    ≤1%→6, ≤3%→4, ≤5%→2, ≤10%→1, else 0
 * LP 池占比           4    ≥20%→4, ≥10%→2, ≥5%→1, else 0
 * </pre>
 */
@Component
public class TokenomicsScorer implements TokenScorer {

    @Override
    public ScoreDimension dimension() {
        return ScoreDimension.TOKENOMICS;
    }

    @Override
    public int score(NewTokenRawData data) {
        int score = 0;

        // Top10 持有者集中度（10分，数据缺失时给基础分）
        Double top10 = toDouble(data.getTop10HolderPct());
        if (top10 == null) {
            score += 5; // 无数据时给中等分
        } else if (top10 <= 20) score += 10;
        else if (top10 <= 30) score += 7;
        else if (top10 <= 40) score += 5;
        else if (top10 <= 50) score += 3;
        else if (top10 <= 70) score += 1;

        // 部署者持仓占比（6分，数据缺失时给中等分）
        Double deployer = toDouble(data.getDeployerHoldingPct());
        if (deployer == null) {
            score += 3; // 无数据时给中等分
        } else if (deployer <= 1) score += 6;
        else if (deployer <= 3) score += 4;
        else if (deployer <= 5) score += 2;
        else if (deployer <= 10) score += 1;

        // LP 池占比（4分）
        Double lpPool = toDouble(data.getLpPoolPct());
        if (lpPool == null) {
            score += 1;
        } else if (lpPool >= 20) score += 4;
        else if (lpPool >= 10) score += 2;
        else if (lpPool >= 5) score += 1;

        return Math.min(score, dimension().getMaxScore());
    }

    private Double toDouble(BigDecimal val) {
        return val != null ? val.doubleValue() : null;
    }
}

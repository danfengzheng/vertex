package com.vertex.service.chain.scorer.impl;

import com.vertex.service.chain.scorer.ScoreDimension;
import com.vertex.service.chain.scorer.TokenScorer;
import com.vertex.service.chain.source.NewTokenRawData;
import org.springframework.stereotype.Component;

/**
 * 链上指标评分器（满分 30 分）
 *
 * <pre>
 * 子项               满分  评分逻辑
 * 持有者数           10    ≥500→10, ≥200→7, ≥100→5, ≥50→3, ≥10→1, else 0
 * 1h 交易数          8     ≥200→8, ≥100→6, ≥50→4, ≥20→2, ≥5→1, else 0
 * 合约已验证         7     verified→7, else 0
 * 流动性锁定         5     locked→5, else 0
 * </pre>
 */
@Component
public class OnChainScorer implements TokenScorer {

    @Override
    public ScoreDimension dimension() {
        return ScoreDimension.ON_CHAIN;
    }

    @Override
    public int score(NewTokenRawData data) {
        int score = 0;

        // 持有者数（10分）
        int holders = data.getHolderCount() != null ? data.getHolderCount() : 0;
        if (holders >= 500) score += 10;
        else if (holders >= 200) score += 7;
        else if (holders >= 100) score += 5;
        else if (holders >= 50) score += 3;
        else if (holders >= 10) score += 1;

        // 1h 交易数（8分）
        int tx1h = data.getTxCount1h() != null ? data.getTxCount1h() : 0;
        if (tx1h >= 200) score += 8;
        else if (tx1h >= 100) score += 6;
        else if (tx1h >= 50) score += 4;
        else if (tx1h >= 20) score += 2;
        else if (tx1h >= 5) score += 1;

        // 合约已验证（7分）
        if (Boolean.TRUE.equals(data.getContractVerified())) score += 7;

        // 流动性锁定（5分）
        if (Boolean.TRUE.equals(data.getLiquidityLocked())) score += 5;

        return Math.min(score, dimension().getMaxScore());
    }
}

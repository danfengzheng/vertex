package com.vertex.service.chain.scorer.impl;

import com.vertex.service.chain.scorer.ScoreDimension;
import com.vertex.service.chain.scorer.TokenScorer;
import com.vertex.service.chain.source.NewTokenRawData;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 市场指标评分器（满分 40 分）
 *
 * <pre>
 * 子项               满分  评分逻辑
 * 流动性 USD         12    ≥100k→12, ≥50k→9, ≥20k→6, ≥5k→3, ≥1k→1, else 0
 * 24h 成交量 USD     10    ≥500k→10, ≥100k→7, ≥50k→5, ≥10k→3, ≥1k→1, else 0
 * 市值 USD           8     ≥10M→8, ≥1M→6, ≥500k→4, ≥100k→2, ≥10k→1, else 0
 * 1h 价格动能        6     ≥20%→6, ≥10%→4, ≥5%→2, ≥0%→1, else 0
 * 买盘压力           4     ≥0.7→4, ≥0.6→3, ≥0.5→2, ≥0.4→1, else 0
 * </pre>
 */
@Component
public class MarketScorer implements TokenScorer {

    @Override
    public ScoreDimension dimension() {
        return ScoreDimension.MARKET;
    }

    @Override
    public int score(NewTokenRawData data) {
        int score = 0;

        // 流动性 USD（12分）
        double liq = toDouble(data.getLiquidityUsd());
        if (liq >= 100_000) score += 12;
        else if (liq >= 50_000) score += 9;
        else if (liq >= 20_000) score += 6;
        else if (liq >= 5_000) score += 3;
        else if (liq >= 1_000) score += 1;

        // 24h 成交量 USD（10分）
        double vol = toDouble(data.getVolume24hUsd());
        if (vol >= 500_000) score += 10;
        else if (vol >= 100_000) score += 7;
        else if (vol >= 50_000) score += 5;
        else if (vol >= 10_000) score += 3;
        else if (vol >= 1_000) score += 1;

        // 市值 USD（8分）
        double mc = toDouble(data.getMarketCapUsd());
        if (mc >= 10_000_000) score += 8;
        else if (mc >= 1_000_000) score += 6;
        else if (mc >= 500_000) score += 4;
        else if (mc >= 100_000) score += 2;
        else if (mc >= 10_000) score += 1;

        // 1h 价格动能（6分）
        double pch1h = toDouble(data.getPriceChange1hPct());
        if (pch1h >= 20) score += 6;
        else if (pch1h >= 10) score += 4;
        else if (pch1h >= 5) score += 2;
        else if (pch1h >= 0) score += 1;

        // 买盘压力（4分）
        double bp = toDouble(data.getBuyPressure1h());
        if (bp >= 0.7) score += 4;
        else if (bp >= 0.6) score += 3;
        else if (bp >= 0.5) score += 2;
        else if (bp >= 0.4) score += 1;

        return Math.min(score, dimension().getMaxScore());
    }

    private double toDouble(BigDecimal val) {
        return val != null ? val.doubleValue() : 0.0;
    }
}

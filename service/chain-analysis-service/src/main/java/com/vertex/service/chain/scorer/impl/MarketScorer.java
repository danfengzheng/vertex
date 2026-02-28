package com.vertex.service.chain.scorer.impl;

import com.vertex.service.chain.scorer.ScoreDimension;
import com.vertex.service.chain.scorer.TokenScorer;
import com.vertex.service.chain.source.NewTokenRawData;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 市场指标评分器（满分 40 分）
 * <p>
 * 根据代币所处市场阶段自动选择评分路径：
 *
 * <h3>路径 A：一级市场（bonding curve 阶段，{@code bondingCurveProgress != null}）</h3>
 * <pre>
 * 子项                   满分  评分逻辑
 * Bonding Curve 进度     18    ≥80%→18（即将毕业）, ≥60%→14, ≥40%→10, ≥20%→6, ≥5%→3, else 1
 * 市值 USD               12    ≥500k→12, ≥100k→8, ≥50k→5, ≥20k→3, ≥5k→1, else 0
 * 社区讨论数(replyCount) 10    ≥200→10, ≥100→7, ≥50→5, ≥20→3, ≥5→1, else 0
 * </pre>
 * Bonding curve 进度越高，说明：① 市场认可度越高；② 即将毕业到 DEX，流动性事件临近；
 * ③ 早期发现高进度代币是一级市场捕捉机会的核心信号。
 *
 * <h3>路径 B：二级市场（已上 DEX，{@code liquidityUsd != null}）</h3>
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
        // 根据 bondingCurveProgress 判断市场阶段
        if (data.getBondingCurveProgress() != null) {
            return scorePrimaryMarket(data);
        }
        return scoreSecondaryMarket(data);
    }

    // ─── 路径 A：一级市场 ──────────────────────────────────

    /**
     * 一级市场评分：核心信号是 bonding curve 进度 + 市值 + 社区热度。
     * <p>
     * bonding curve 进度权重最高（18/40），因为：
     * <ul>
     *   <li>进度高 → 已获得市场资金验证</li>
     *   <li>进度接近 100% → 即将毕业上 DEX，是最强的价格催化剂</li>
     *   <li>早于毕业发现 = 最佳进入时机</li>
     * </ul>
     */
    private int scorePrimaryMarket(NewTokenRawData data) {
        int score = 0;

        // Bonding Curve 进度（18分）
        double bcp = data.getBondingCurveProgress();
        if (bcp >= 80) score += 18;      // 即将毕业，最强信号
        else if (bcp >= 60) score += 14;
        else if (bcp >= 40) score += 10;
        else if (bcp >= 20) score += 6;
        else if (bcp >= 5)  score += 3;
        else                score += 1;  // 刚创建，给最低基分

        // 市值 USD（12分）—— 有机需求的直接体现
        double mc = toDouble(data.getMarketCapUsd());
        if (mc >= 500_000)     score += 12;
        else if (mc >= 100_000) score += 8;
        else if (mc >= 50_000)  score += 5;
        else if (mc >= 20_000)  score += 3;
        else if (mc >= 5_000)   score += 1;

        // 社区讨论数（10分）—— 热度与传播力代理指标
        int rc = data.getReplyCount() != null ? data.getReplyCount() : 0;
        if (rc >= 200)      score += 10;
        else if (rc >= 100) score += 7;
        else if (rc >= 50)  score += 5;
        else if (rc >= 20)  score += 3;
        else if (rc >= 5)   score += 1;

        return Math.min(score, dimension().getMaxScore());
    }

    // ─── 路径 B：二级市场（已上 DEX）────────────────────────

    private int scoreSecondaryMarket(NewTokenRawData data) {
        int score = 0;

        // 流动性 USD（12分）
        double liq = toDouble(data.getLiquidityUsd());
        if (liq >= 100_000)     score += 12;
        else if (liq >= 50_000) score += 9;
        else if (liq >= 20_000) score += 6;
        else if (liq >= 5_000)  score += 3;
        else if (liq >= 1_000)  score += 1;

        // 24h 成交量 USD（10分）
        double vol = toDouble(data.getVolume24hUsd());
        if (vol >= 500_000)      score += 10;
        else if (vol >= 100_000) score += 7;
        else if (vol >= 50_000)  score += 5;
        else if (vol >= 10_000)  score += 3;
        else if (vol >= 1_000)   score += 1;

        // 市值 USD（8分）
        double mc = toDouble(data.getMarketCapUsd());
        if (mc >= 10_000_000)   score += 8;
        else if (mc >= 1_000_000) score += 6;
        else if (mc >= 500_000) score += 4;
        else if (mc >= 100_000) score += 2;
        else if (mc >= 10_000)  score += 1;

        // 1h 价格动能（6分）
        double pch1h = toDouble(data.getPriceChange1hPct());
        if (pch1h >= 20) score += 6;
        else if (pch1h >= 10) score += 4;
        else if (pch1h >= 5)  score += 2;
        else if (pch1h >= 0)  score += 1;

        // 买盘压力（4分）
        double bp = toDouble(data.getBuyPressure1h());
        if (bp >= 0.7)      score += 4;
        else if (bp >= 0.6) score += 3;
        else if (bp >= 0.5) score += 2;
        else if (bp >= 0.4) score += 1;

        return Math.min(score, dimension().getMaxScore());
    }

    private double toDouble(BigDecimal val) {
        return val != null ? val.doubleValue() : 0.0;
    }
}

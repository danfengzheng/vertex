package com.vertex.service.chain.scorer.impl;

import com.vertex.service.chain.scorer.ScoreDimension;
import com.vertex.service.chain.scorer.TokenScorer;
import com.vertex.service.chain.source.NewTokenRawData;
import org.springframework.stereotype.Component;

/**
 * 发现信号评分器（满分 10 分）
 * <p>
 * 针对不同数据来源使用两套评分路径：
 *
 * <h3>新币发现模式（bnb_primary / solana 等）</h3>
 * 衡量代币的"早期发现价值"：越新、社区越活跃、越接近毕业的代币得分越高。
 * <pre>
 * 子项               满分  评分逻辑
 * 存在时长           5     ≤15min→5（极新）, ≤30min→4, ≤60min→3, ≤120min→2, ≤360min→1, else 0
 * 一级市场信号       3     bondingCurveProgress≥70%→3, ≥30%→2, pumpFunListed=true→1, else 0
 * 社区热度           2     replyCount≥50→2, ≥10→1, else 0
 * </pre>
 *
 * <h3>潜力筛选模式（bnb_alpha / bnb_trending）</h3>
 * 衡量代币的"当下动量信号"：成交量高、买盘强、价格有上涨趋势的代币得分高。
 * 不因代币年龄大而减分。
 * <pre>
 * 子项               满分  评分逻辑
 * 换手率             5     volume24h/liquidity ≥ 2.0→5, ≥ 1.0→4, ≥ 0.5→3, ≥ 0.2→2, > 0→1
 * 价格动量           3     1h涨幅 ≥ 10%→3, ≥ 5%→2, ≥ 0%→1, else 0
 * 买盘强度           2     buyPressure ≥ 0.7→2, ≥ 0.55→1, else 0
 * </pre>
 */
@Component
public class NoveltyScorer implements TokenScorer {

    private static final java.util.Set<String> SCREENING_SOURCES =
            java.util.Set.of("bnb_alpha", "bnb_trending");

    @Override
    public ScoreDimension dimension() {
        return ScoreDimension.NOVELTY;
    }

    @Override
    public int score(NewTokenRawData data) {
        boolean isScreening = data.getDataSource() != null
                && SCREENING_SOURCES.contains(data.getDataSource());
        return isScreening ? scoreMomentum(data) : scoreNovelty(data);
    }

    // ─── 新币发现模式：早期发现价值 ────────────────────────

    private int scoreNovelty(NewTokenRawData data) {
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

    // ─── 潜力筛选模式：当下动量信号 ────────────────────────

    private int scoreMomentum(NewTokenRawData data) {
        int score = 0;

        // 换手率（volume24h / liquidity），5 分
        double liq = data.getLiquidityUsd() != null ? data.getLiquidityUsd().doubleValue() : 0;
        double vol = data.getVolume24hUsd() != null ? data.getVolume24hUsd().doubleValue() : 0;
        if (liq > 0 && vol > 0) {
            double ratio = vol / liq;
            if (ratio >= 2.0)      score += 5;
            else if (ratio >= 1.0) score += 4;
            else if (ratio >= 0.5) score += 3;
            else if (ratio >= 0.2) score += 2;
            else                   score += 1;
        }

        // 1h 价格动量（3 分）
        double p1h = data.getPriceChange1hPct() != null ? data.getPriceChange1hPct().doubleValue() : Double.NaN;
        if (!Double.isNaN(p1h)) {
            if (p1h >= 10)     score += 3;
            else if (p1h >= 5) score += 2;
            else if (p1h >= 0) score += 1;
        }

        // 买盘强度（2 分）
        double bp = data.getBuyPressure1h() != null ? data.getBuyPressure1h().doubleValue() : 0;
        if (bp >= 0.7)      score += 2;
        else if (bp >= 0.55) score += 1;

        return Math.min(score, dimension().getMaxScore());
    }
}

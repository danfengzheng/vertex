package com.vertex.service.farb.regime;

import com.vertex.service.farb.entity.FarbStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 资金费套利 - Stage 2 综合评分。
 * <p>
 * DESIGN §4.6：Stage 1 通过后按此 score 排序，取 top K 入场。
 * 权重固定（V1 简化），未来 v2 加参数化。
 * </p>
 *
 * 评分组成（权重合计 1.0）：
 * <pre>
 *   0.40 × normalize(avg_recent_funding × oi_multiplier)
 *   0.20 × normalize(funding_momentum)
 *   0.15 × normalize(basis_change_rate)  ← basis 向有利方向变动加分
 *   0.15 × reverse(lsr_extremeness_score)
 *   0.10 × sign(taker_bsr − 1) × confidence(taker_bsr)
 * </pre>
 * 归一化：把每个信号映射到 [0, 100] 后再乘权重，最终 score 范围 [0, 100+]。
 */
@Slf4j
@Component
public class CandidateScorer {

    /**
     * 计算候选综合评分 + 生成入场原因文本。
     */
    public ScoredCandidate score(FarbStrategy strategy, RegimeMetrics m) {
        // ─── 1) Funding × OI multiplier ─────────────────────────────
        double oiMult;
        if (m.getOiChange2hPct() > 5.0) {
            oiMult = 1.2;      // 2h OI 上涨 5%+ → 新钱进场，加分
        } else if (m.getOiChange2hPct() < -3.0) {
            oiMult = 0.5;      // 2h OI 下跌 3%+ → 空头离场，很可能到顶
        } else {
            oiMult = 1.0;
        }
        // funding 归一化：0.02% (2 bps) → 40 分基线；0.15% (15 bps) → 100 分饱和
        double fundingScore = Math.min(100, m.getAvgRecentFundingBps() * oiMult / 15.0 * 100);

        // ─── 2) Funding momentum ───────────────────────────────────
        // 正 momentum → 加速 → 加分；负 → 减速 → 扣分。归一化：±5 bps → 100/-100
        double momentumScore = 50 + Math.max(-50, Math.min(50, m.getFundingMomentumBps() * 10));

        // ─── 3) Basis 变化率 ───────────────────────────────────────
        // basis 上升 → 下次 funding 将升 → 加分。归一化：±10 bps → 100/-100
        double basisDeltaScore = 50 + Math.max(-50, Math.min(50, m.getBasisDelta2hBps() * 5));

        // ─── 4) LSR 反向（极端 = 反转前兆 → 减分）───────────────────
        double lsr = m.getLsr();
        double lsrScore;
        if (lsr <= 0) {
            lsrScore = 50;                                  // 数据缺失 → 中性
        } else if (lsr >= 0.7 && lsr <= 2.0) {
            lsrScore = 100;                                 // 均衡区间最高分
        } else {
            // 越极端越低分
            double dev = Math.min(Math.abs(lsr - 1.4) / 1.4, 1.0);
            lsrScore = Math.max(0, 100 - dev * 100);
        }

        // ─── 5) Taker BSR 方向确认 ─────────────────────────────────
        double takerScore;
        if (m.getTakerBsr() <= 0) {
            takerScore = 50;
        } else {
            double delta = m.getTakerBsr() - 1.0;
            // > 1（买盘强） + funding 正 = 确认 → 加分
            // < 1 = 背离 → 扣分
            takerScore = 50 + Math.max(-50, Math.min(50, delta * 100));
        }

        // ─── 加权 ───────────────────────────────────────────────────
        double totalScore =
                0.40 * fundingScore
              + 0.20 * momentumScore
              + 0.15 * basisDeltaScore
              + 0.15 * lsrScore
              + 0.10 * takerScore;

        // ─── 生成入场原因文本（前端展示）────────────────────────────
        StringBuilder reason = new StringBuilder();
        reason.append(String.format("funding=%.2fbps(hit=%.0f%%)",
                m.getAvgRecentFundingBps(), m.getHitRatio() * 100));
        if (oiMult > 1.0) reason.append(" +OI↑");
        if (oiMult < 1.0) reason.append(" -OI↓");
        if (m.getFundingMomentumBps() > 1.0) reason.append(String.format(" +momentum+%.1f", m.getFundingMomentumBps()));
        if (m.getBasisDelta2hBps() > 5.0) reason.append(String.format(" +basis+%.1f", m.getBasisDelta2hBps()));
        if (m.getLsr() > 0 && (m.getLsr() < 0.7 || m.getLsr() > 2.0)) {
            reason.append(String.format(" ⚠lsr=%.2f", m.getLsr()));
        }

        return new ScoredCandidate(totalScore, reason.toString(),
                fundingScore, momentumScore, basisDeltaScore, lsrScore, takerScore);
    }

    public record ScoredCandidate(
            double totalScore,
            String reason,
            double fundingScore,
            double momentumScore,
            double basisDeltaScore,
            double lsrScore,
            double takerScore
    ) {}
}

package com.vertex.service.farb.regime;

import com.vertex.service.farb.signal.FarbSignalCache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 预测反转打分器（v2）。
 * <p>
 * 输入 symbol，读 SignalCache 最新态，输出 [0.0, 1.0] 的 healthScore：
 * <ul>
 *   <li>1.0 = regime 健康，funding 会继续保持</li>
 *   <li>0.5 = 中性</li>
 *   <li>0.0 = 极度可能反转</li>
 * </ul>
 * 五因子加权：
 * <table>
 *   <tr><th>因子</th><th>权重</th><th>健康信号</th><th>反转信号</th></tr>
 *   <tr><td>fundingSlope</td><td>0.35</td><td>最近 funding 均值 ≥ 更早均值</td><td>下降</td></tr>
 *   <tr><td>basisWiden</td><td>0.25</td><td>basis 稳定 / 收窄</td><td>快速走宽（premium 消失）</td></tr>
 *   <tr><td>oiTrend</td><td>0.15</td><td>OI 稳定或上升</td><td>快速下降（deleverage）</td></tr>
 *   <tr><td>lsrTrend</td><td>0.15</td><td>多头占比稳定</td><td>多头快速消退</td></tr>
 *   <tr><td>takerBSR</td><td>0.10</td><td>taker buy > sell</td><td>taker sell 主导</td></tr>
 * </table>
 * 缺数据的因子权重会重分配（renormalize）而非直接扣分，避免早期采集稀疏时误报。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReversalPredictor {

    private final FarbSignalCache cache;

    /** 主入口：返回 healthScore ∈ [0, 1]。数据完全缺失时返回 0.5（中性）。*/
    public double score(String symbol) {
        double[] scores = new double[5];
        double[] weights = {0.35, 0.25, 0.15, 0.15, 0.10};
        boolean[] avail = new boolean[5];

        // 1) fundingSlope
        Double f = fundingSlopeScore(symbol);
        if (f != null) { scores[0] = f; avail[0] = true; }

        // 2) basisWiden
        Double b = basisWidenScore(symbol);
        if (b != null) { scores[1] = b; avail[1] = true; }

        // 3) oiTrend
        Double oi = oiTrendScore(symbol);
        if (oi != null) { scores[2] = oi; avail[2] = true; }

        // 4) lsrTrend
        Double l = lsrTrendScore(symbol);
        if (l != null) { scores[3] = l; avail[3] = true; }

        // 5) takerBSR
        Double t = takerBsrScore(symbol);
        if (t != null) { scores[4] = t; avail[4] = true; }

        double sumW = 0, sumWS = 0;
        for (int i = 0; i < 5; i++) {
            if (avail[i]) {
                sumW += weights[i];
                sumWS += weights[i] * scores[i];
            }
        }
        if (sumW < 1e-9) return 0.5;   // 完全无数据 → 中性
        return sumWS / sumW;
    }

    // ─── 各因子打分（返回 null 表示数据不足）────────────────────────────

    /** 最近 3 次 funding 均值 vs 更早 3 次均值。>0 → 健康(1.0)，<0 → 反转(0.0)，线性映射 */
    private Double fundingSlopeScore(String symbol) {
        List<FarbSignalCache.Entry<FarbSignalCache.FundingRecord>> hist = cache.recentFunding(symbol, 6);
        if (hist.size() < 6) return null;
        // recentFunding 返回按 ts 升序（内部 LinkedHashMap 顺序）；末尾为最新
        double later = 0, earlier = 0;
        for (int i = 3; i < 6; i++) later += toBps(hist.get(i));
        for (int i = 0; i < 3; i++) earlier += toBps(hist.get(i));
        later /= 3; earlier /= 3;
        double delta = later - earlier;   // 正 = 健康
        // 映射：Δ >= +5 bps → 1.0；Δ <= -5 bps → 0.0；中间线性
        return clamp01((delta + 5) / 10);
    }

    /** basis 走宽速度。取 latest premium 的 basisBps 绝对值 & 与最近历史 median 差异。 */
    private Double basisWidenScore(String symbol) {
        var e = cache.latestPremium(symbol);
        if (e == null) return null;
        double basis = e.value().basisBps();   // (mark - index) / index * 10000
        // 阈值：|basis| <= 5bps → 1.0（稳定）；|basis| >= 40bps → 0.0（很宽）
        double abs = Math.abs(basis);
        return clamp01(1 - (abs - 5) / 35);
    }

    /** OI 6h 变化率。dropRate = (latest - past6h) / past6h。负值大 → 反转 */
    private Double oiTrendScore(String symbol) {
        var latest = cache.latestOi(symbol);
        if (latest == null) return null;
        long past = latest.ts() - 6 * 3600_000L;
        var older = cache.oiAt(symbol, past);
        if (older == null) return null;
        double oldOi = older.value().openInterest();
        double newOi = latest.value().openInterest();
        if (oldOi < 1e-9) return null;
        double changePct = (newOi - oldOi) / oldOi;
        // +5% → 1.0；-10% → 0.0；线性
        return clamp01((changePct + 0.10) / 0.15);
    }

    /** LSR 长短比。>1 且稳定 → 健康；<0.9 或下降 → 反转 */
    private Double lsrTrendScore(String symbol) {
        var e = cache.latestLsr(symbol);
        if (e == null) return null;
        double lsr = e.value().longShortRatio();
        // lsr >= 1.5 → 1.0；lsr <= 0.7 → 0.0
        return clamp01((lsr - 0.7) / 0.8);
    }

    /** Taker buy/sell 比。>1 = 买主导 = 健康 */
    private Double takerBsrScore(String symbol) {
        var e = cache.latestTbsr(symbol);
        if (e == null) return null;
        double bsr = e.value().buySellRatio();
        // bsr >= 1.2 → 1.0；bsr <= 0.8 → 0.0
        return clamp01((bsr - 0.8) / 0.4);
    }

    // ─── utils ────────────────────────────────────────────────────────

    private static double toBps(FarbSignalCache.Entry<FarbSignalCache.FundingRecord> e) {
        var v = e.value();
        return v.rate() * (8.0 / Math.max(1, v.intervalHours())) * 10000.0;
    }

    private static double clamp01(double x) {
        if (Double.isNaN(x)) return 0.5;
        if (x < 0) return 0;
        if (x > 1) return 1;
        return x;
    }
}

package com.vertex.service.farb.regime;

import com.vertex.service.farb.signal.FarbSignalCache;
import com.vertex.service.farb.signal.FarbSignalCache.Entry;
import com.vertex.service.farb.signal.FarbSignalCache.FundingRecord;
import com.vertex.service.farb.signal.FarbSignalCache.LsrRecord;
import com.vertex.service.farb.signal.FarbSignalCache.OiRecord;
import com.vertex.service.farb.signal.FarbSignalCache.PremiumRecord;
import com.vertex.service.farb.signal.FarbSignalCache.TakerBsrRecord;
import com.vertex.service.farb.signal.FarbSignalCache.VolRecord;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.StringJoiner;

/**
 * 资金费套利 - Regime 综合指标计算器。
 * <p>从 {@link FarbSignalCache} 拿数据，产出 {@link RegimeMetrics}，供 RegimeScanner（Sprint 3）过滤/评分。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RegimeMetricsCalculator {

    private final FarbSignalCache cache;

    /**
     * 计算某 symbol 的完整 regime metrics。
     * 缺失数据不抛异常，用 0 / NaN 兜底，通过 hasCompleteData + missingSignals 报告。
     *
     * @param symbol             币安 symbol（如 BTCUSDT）
     * @param lookbackSettles    Funding regime 判定用的回看次数
     * @param minFundingBps8hEq  达标阈值（8h 归一化 bps），用于 hitRatio 判定
     */
    public RegimeMetrics compute(String symbol, int lookbackSettles, double minFundingBps8hEq) {
        StringJoiner missing = new StringJoiner(",");
        RegimeMetrics.RegimeMetricsBuilder b = RegimeMetrics.builder().symbol(symbol);

        // ─── Funding ────────────────────────────────────────────────────
        List<Entry<FundingRecord>> recent = cache.recentFunding(symbol, lookbackSettles);
        if (recent.isEmpty()) {
            missing.add("funding");
        }
        int n = recent.size();
        b.fundingSampleCount(n);
        double sum = 0, latest = 0, latestUnnormalized = 0;
        int hits = 0;
        double[] normalized = new double[n];
        for (int i = 0; i < n; i++) {
            var fr = recent.get(i).value();
            double norm8h = fundingTo8hEqBps(fr.rate(), fr.intervalHours());
            normalized[i] = norm8h;
            sum += norm8h;
            if (norm8h >= minFundingBps8hEq) hits++;
            if (i == n - 1) {
                latest = norm8h;
                latestUnnormalized = fr.rate();
            }
        }
        double avg = n > 0 ? sum / n : 0;
        double hitRatio = n > 0 ? (double) hits / n : 0;
        double momentum = computeMomentum(normalized, 3);
        b.avgRecentFundingBps(avg).hitRatio(hitRatio).latestFundingBps(latest).fundingMomentumBps(momentum);

        // ─── Basis ──────────────────────────────────────────────────────
        Entry<PremiumRecord> premLatest = cache.latestPremium(symbol);
        double curBasis = 0, basisDelta2h = 0;
        if (premLatest == null) {
            missing.add("premium");
        } else {
            curBasis = premLatest.value().basisBps();
            Entry<PremiumRecord> prem2hAgo = cache.premiumAt(symbol,
                    premLatest.ts() - 2 * 60 * 60 * 1000L);
            if (prem2hAgo != null) {
                basisDelta2h = curBasis - prem2hAgo.value().basisBps();
            }
        }
        b.currentBasisBps(curBasis).basisDelta2hBps(basisDelta2h);

        // ─── OI ─────────────────────────────────────────────────────────
        Entry<OiRecord> oiLatest = cache.latestOi(symbol);
        double curOi = 0, oi2h = 0, oi24h = 0;
        if (oiLatest == null) {
            missing.add("oi");
        } else {
            curOi = oiLatest.value().openInterest();
            Entry<OiRecord> oi2hAgo = cache.oiAt(symbol, oiLatest.ts() - 2 * 60 * 60 * 1000L);
            Entry<OiRecord> oi24hAgo = cache.oiAt(symbol, oiLatest.ts() - 24 * 60 * 60 * 1000L);
            if (oi2hAgo != null && oi2hAgo.value().openInterest() > 0) {
                oi2h = (curOi - oi2hAgo.value().openInterest()) / oi2hAgo.value().openInterest() * 100.0;
            }
            if (oi24hAgo != null && oi24hAgo.value().openInterest() > 0) {
                oi24h = (curOi - oi24hAgo.value().openInterest()) / oi24hAgo.value().openInterest() * 100.0;
            }
        }
        b.currentOi(curOi).oiChange2hPct(oi2h).oiChange24hPct(oi24h);

        // ─── LSR ────────────────────────────────────────────────────────
        Entry<LsrRecord> lsrLatest = cache.latestLsr(symbol);
        double lsr = 0;
        if (lsrLatest == null) {
            missing.add("lsr");
        } else {
            lsr = lsrLatest.value().longShortRatio();
        }
        b.lsr(lsr);

        // ─── Taker BSR ──────────────────────────────────────────────────
        Entry<TakerBsrRecord> tbsr = cache.latestTbsr(symbol);
        double takerBsr = 0;
        if (tbsr == null) {
            missing.add("tbsr");
        } else {
            takerBsr = tbsr.value().buySellRatio();
        }
        b.takerBsr(takerBsr);

        // ─── Vol ────────────────────────────────────────────────────────
        Entry<VolRecord> vol = cache.latestVol(symbol);
        double vol24 = 0, vol72 = 0, spike = 0;
        if (vol == null) {
            missing.add("vol");
        } else {
            vol24 = vol.value().vol24h();
            vol72 = vol.value().vol72h();
            spike = vol72 > 0 ? vol24 / vol72 : 0;
        }
        b.vol24hAnnualized(vol24).vol72hAnnualized(vol72).volSpikeRatio(spike);

        // ─── 完整度 ──────────────────────────────────────────────────────
        String missingStr = missing.toString();
        b.hasCompleteData(missingStr.isEmpty()).missingSignals(missingStr);

        return b.build();
    }

    // ─── 静态工具 ────────────────────────────────────────────────────────

    /** 把 raw funding rate（小数）折算到 8h 等效 bps */
    public static double fundingTo8hEqBps(double rawRate, int intervalHours) {
        if (intervalHours <= 0) intervalHours = 8;
        // rate 是小数（如 0.0001 = 0.01%），bps = rate × 10000
        // 8h 等效 = raw × (8 / intervalHours) × 10000
        return rawRate * (8.0 / intervalHours) * 10000.0;
    }

    /** momentum: 最近 window 次均值 − 更前 window 次均值 */
    private static double computeMomentum(double[] arr, int window) {
        int n = arr.length;
        if (n < 2 * window) return 0;
        double recent = 0, prior = 0;
        for (int i = n - window; i < n; i++) recent += arr[i];
        for (int i = n - 2 * window; i < n - window; i++) prior += arr[i];
        return (recent - prior) / window;
    }
}

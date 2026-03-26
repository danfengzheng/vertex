package com.vertex.service.strategy.indicator.impl;

import com.vertex.model.entity.quote.KLine;
import com.vertex.model.entity.strategy.IndicatorType;
import com.vertex.service.strategy.indicator.IndicatorResult;
import com.vertex.service.strategy.indicator.IndicatorResult.SignalSuggestion;
import com.vertex.service.strategy.indicator.TechnicalIndicator;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 背离指标 (Divergence)
 * <p>
 * 通过对比价格摆动高低点与 RSI 对应值，检测趋势与动量之间的背离。
 * 额外提供两种前瞻性输出，帮助提前识别背离形成过程：
 *
 * <h3>信号类型</h3>
 * <ul>
 *   <li><b>顶背离（Bearish Divergence）</b>：价格创新高，RSI 未创新高 → SELL<br>
 *       需要右侧 swingStrength 根K线完整确认摆动高点，天然滞后。</li>
 *   <li><b>底背离（Bullish Divergence）</b>：价格创新低，RSI 未创新低 → BUY<br>
 *       同上。</li>
 *   <li><b>顶背离形成中（Bearish Divergence Forming）</b>：当前K线已满足左侧高点条件，<br>
 *       且价格高于上一已确认摆动高点而 RSI 低于该高点 → 提前 swingStrength 根预警。</li>
 *   <li><b>底背离形成中（Bullish Divergence Forming）</b>：同上，针对低点。</li>
 *   <li><b>顶背离压力分（Bearish Pressure）</b>：0-100 连续分，基于近期价格斜率为正<br>
 *       而 RSI 斜率为负的程度量化，无需摆动点即可实时输出。</li>
 *   <li><b>底背离压力分（Bullish Pressure）</b>：0-100，价格斜率为负而 RSI 斜率为正。</li>
 * </ul>
 *
 * <h3>参数</h3>
 * <ul>
 *   <li>{@code lookback}（默认 20）：向前搜索摆动点的K线窗口大小</li>
 *   <li>{@code rsiPeriod}（默认 14）：内部 RSI 计算周期</li>
 *   <li>{@code swingStrength}（默认 2）：摆动点左右各需确认的K线数</li>
 *   <li>{@code pressureWindow}（默认 max(10, lookback/2)）：计算压力分使用的最近 K 线窗口</li>
 * </ul>
 *
 * <h3>输出字段</h3>
 * <ul>
 *   <li>{@code bearishDivergence}：1.0 = 已确认顶背离，0.0 = 无（滞后 swingStrength 根）</li>
 *   <li>{@code bullishDivergence}：1.0 = 已确认底背离，0.0 = 无</li>
 *   <li>{@code bearishDivergenceForming}：1.0 = 顶背离形成中（提前预警），0.0 = 无</li>
 *   <li>{@code bullishDivergenceForming}：1.0 = 底背离形成中，0.0 = 无</li>
 *   <li>{@code bearishPressure}：0-100，价格↑ RSI↓ 的动能分歧强度</li>
 *   <li>{@code bullishPressure}：0-100，价格↓ RSI↑ 的动能分歧强度</li>
 * </ul>
 *
 * <h3>典型出场配置（由保守到激进）</h3>
 * <pre>
 * // 保守：等完整确认（现有逻辑）
 * {"field": "bearishDivergence",        "op": "GTE", "threshold": 1.0}
 *
 * // 适中：形成中即预警（提前 swingStrength 根）
 * {"field": "bearishDivergenceForming", "op": "GTE", "threshold": 1.0}
 *
 * // 激进：动能持续分歧超 70 分即出场
 * {"field": "bearishPressure",          "op": "GTE", "threshold": 70}
 * </pre>
 */
@Component
public class DivergenceIndicator implements TechnicalIndicator {

    @Override
    public IndicatorType type() {
        return IndicatorType.DIVERGENCE;
    }

    @Override
    public int requiredDataPoints(Map<String, Object> params) {
        int lookback = getParam(params, "lookback", 20);
        int rsiPeriod = getParam(params, "rsiPeriod", 14);
        int swingStrength = getParam(params, "swingStrength", 2);
        return rsiPeriod + lookback + swingStrength * 2 + 5;
    }

    @Override
    public IndicatorResult calculate(List<KLine> klines, Map<String, Object> params) {
        int lookback      = getParam(params, "lookback", 20);
        int rsiPeriod     = getParam(params, "rsiPeriod", 14);
        int swingStrength = getParam(params, "swingStrength", 2);

        int n = klines.size();
        double[] closes = new double[n];
        for (int i = 0; i < n; i++) {
            closes[i] = klines.get(i).getClose().doubleValue();
        }

        // ── Step 1: 计算完整 RSI 序列 ─────────────────────────────────────────
        double[] rsiSeries = computeRsiSeries(closes, rsiPeriod);

        // ── Step 2: 确定有效搜索窗口 ──────────────────────────────────────────
        // 最后 swingStrength 根K线无法确认（缺少右侧K线），从 confirmedEnd 向前 lookback 根
        int confirmedEnd   = n - 1 - swingStrength;
        int confirmedStart = Math.max(rsiPeriod + swingStrength, confirmedEnd - lookback);

        if (confirmedEnd < confirmedStart) {
            return neutralResult();
        }

        // ── Step 3: 找出窗口内所有已确认的摆动高点和低点 ───────────────────────
        List<SwingPoint> swingHighs = new ArrayList<>();
        List<SwingPoint> swingLows  = new ArrayList<>();

        for (int i = confirmedStart; i <= confirmedEnd; i++) {
            if (rsiSeries[i] <= 0) continue;
            if (isSwingHigh(closes, i, swingStrength)) {
                swingHighs.add(new SwingPoint(closes[i], rsiSeries[i]));
            }
            if (isSwingLow(closes, i, swingStrength)) {
                swingLows.add(new SwingPoint(closes[i], rsiSeries[i]));
            }
        }

        // ── Step 4: 已确认背离 ────────────────────────────────────────────────
        double bearishDivergence = 0.0;
        double bullishDivergence = 0.0;

        if (swingHighs.size() >= 2) {
            SwingPoint prev = swingHighs.get(swingHighs.size() - 2);
            SwingPoint curr = swingHighs.get(swingHighs.size() - 1);
            if (curr.price > prev.price && curr.rsi < prev.rsi) {
                bearishDivergence = 1.0;
            }
        }

        if (swingLows.size() >= 2) {
            SwingPoint prev = swingLows.get(swingLows.size() - 2);
            SwingPoint curr = swingLows.get(swingLows.size() - 1);
            if (curr.price < prev.price && curr.rsi > prev.rsi) {
                bullishDivergence = 1.0;
            }
        }

        // ── Step 5: 背离形成中（左侧确认 + 当前K线为潜在摆动点）────────────────
        double currentClose = closes[n - 1];
        double currentRsi   = rsiSeries[n - 1];

        double bearishDivergenceForming = 0.0;
        double bullishDivergenceForming = 0.0;

        if (currentRsi > 0) {
            // 当前K线是否满足左侧高点条件（高于左侧 swingStrength 根）
            boolean isPotentialHigh = isPotentialSwingHigh(closes, n - 1, swingStrength);
            boolean isPotentialLow  = isPotentialSwingLow(closes, n - 1, swingStrength);

            if (isPotentialHigh && !swingHighs.isEmpty()) {
                SwingPoint lastConfirmedHigh = swingHighs.get(swingHighs.size() - 1);
                // 价格创新高但 RSI 低于上一摆动高点的 RSI
                if (currentClose > lastConfirmedHigh.price && currentRsi < lastConfirmedHigh.rsi) {
                    bearishDivergenceForming = 1.0;
                }
            }

            if (isPotentialLow && !swingLows.isEmpty()) {
                SwingPoint lastConfirmedLow = swingLows.get(swingLows.size() - 1);
                // 价格创新低但 RSI 高于上一摆动低点的 RSI
                if (currentClose < lastConfirmedLow.price && currentRsi > lastConfirmedLow.rsi) {
                    bullishDivergenceForming = 1.0;
                }
            }
        }

        // ── Step 6: 动能分歧压力分（线性回归斜率对比）──────────────────────────
        int pressureWindow = getParam(params, "pressureWindow", Math.max(10, lookback / 2));
        int pwStart = Math.max(rsiPeriod, n - pressureWindow);
        double[] bearishBullishPressure = computePressure(closes, rsiSeries, pwStart, n - 1);

        // ── Step 7: suggestion 向后兼容 ──────────────────────────────────────
        SignalSuggestion suggestion;
        if (bearishDivergence > 0) {
            suggestion = SignalSuggestion.SELL;
        } else if (bullishDivergence > 0) {
            suggestion = SignalSuggestion.BUY;
        } else if (bearishDivergenceForming > 0) {
            suggestion = SignalSuggestion.SELL;
        } else if (bullishDivergenceForming > 0) {
            suggestion = SignalSuggestion.BUY;
        } else {
            suggestion = SignalSuggestion.NEUTRAL;
        }

        Map<String, Double> values = new HashMap<>();
        values.put("bearishDivergence",        bearishDivergence);
        values.put("bullishDivergence",         bullishDivergence);
        values.put("bearishDivergenceForming",  bearishDivergenceForming);
        values.put("bullishDivergenceForming",  bullishDivergenceForming);
        values.put("bearishPressure",           bearishBullishPressure[0]);
        values.put("bullishPressure",           bearishBullishPressure[1]);

        return IndicatorResult.builder()
                .type(IndicatorType.DIVERGENCE)
                .values(values)
                .suggestion(suggestion)
                .build();
    }

    // ─── 摆动点判断 ────────────────────────────────────────────────────────────

    /** 已确认摆动高点：左右各 strength 根都严格低于 index */
    private boolean isSwingHigh(double[] prices, int index, int strength) {
        for (int j = 1; j <= strength; j++) {
            if (index - j < 0 || index + j >= prices.length) return false;
            if (prices[index - j] >= prices[index] || prices[index + j] >= prices[index]) return false;
        }
        return true;
    }

    /** 已确认摆动低点：左右各 strength 根都严格高于 index */
    private boolean isSwingLow(double[] prices, int index, int strength) {
        for (int j = 1; j <= strength; j++) {
            if (index - j < 0 || index + j >= prices.length) return false;
            if (prices[index - j] <= prices[index] || prices[index + j] <= prices[index]) return false;
        }
        return true;
    }

    /**
     * 潜在摆动高点（仅验证左侧，右侧尚未收盘）：
     * index 处的价格严格高于左侧 strength 根K线
     */
    private boolean isPotentialSwingHigh(double[] prices, int index, int strength) {
        for (int j = 1; j <= strength; j++) {
            if (index - j < 0) return false;
            if (prices[index - j] >= prices[index]) return false;
        }
        return true;
    }

    /**
     * 潜在摆动低点（仅验证左侧）：
     * index 处的价格严格低于左侧 strength 根K线
     */
    private boolean isPotentialSwingLow(double[] prices, int index, int strength) {
        for (int j = 1; j <= strength; j++) {
            if (index - j < 0) return false;
            if (prices[index - j] <= prices[index]) return false;
        }
        return true;
    }

    // ─── 压力分计算 ─────────────────────────────────────────────────────────────

    /**
     * 计算动能分歧压力分。
     * <p>
     * 在 [start, end] 区间上分别对价格和 RSI 做线性回归，取各自斜率：
     * <ul>
     *   <li>顶背离压力：价格斜率 > 0 且 RSI 斜率 < 0，两者幅度的几何均值映射到 0-100</li>
     *   <li>底背离压力：价格斜率 < 0 且 RSI 斜率 > 0</li>
     * </ul>
     * 归一化参考：价格斜率以"每根K线变化占均价的百分比"衡量，上限取 1%/bar；
     * RSI 斜率上限取 2 RSI点/bar（超出均截断为满分）。
     * </p>
     *
     * @return double[2]：[0] = bearishPressure (0-100)，[1] = bullishPressure (0-100)
     */
    private double[] computePressure(double[] closes, double[] rsiSeries, int start, int end) {
        int len = end - start + 1;
        if (len < 3) return new double[]{0.0, 0.0};

        // 过滤掉 RSI 未预热的部分（rsiSeries[i] == 0 表示未预热）
        // 找出实际可用的起始位置
        int actualStart = start;
        while (actualStart <= end && rsiSeries[actualStart] <= 0) {
            actualStart++;
        }
        len = end - actualStart + 1;
        if (len < 3) return new double[]{0.0, 0.0};

        double slopePrice = linearSlope(closes, actualStart, end);
        double slopeRsi   = linearSlope(rsiSeries, actualStart, end);

        // 价格斜率转为 %/bar（相对于窗口均价）
        double avgClose = 0;
        for (int i = actualStart; i <= end; i++) avgClose += closes[i];
        avgClose /= len;
        if (avgClose == 0) return new double[]{0.0, 0.0};
        double slopePricePct = slopePrice / avgClose * 100.0; // %/bar

        double bearishPressure = 0.0;
        double bullishPressure = 0.0;

        // 顶背离压力：价格在涨，RSI 在跌
        if (slopePricePct > 0 && slopeRsi < 0) {
            // 归一化：价格斜率上限 1%/bar → 1.0；RSI 斜率下限 -2pt/bar → 1.0
            double pricePart = Math.min(1.0, slopePricePct / 1.0);
            double rsiPart   = Math.min(1.0, -slopeRsi / 2.0);
            bearishPressure  = Math.round(Math.sqrt(pricePart * rsiPart) * 100.0);
        }

        // 底背离压力：价格在跌，RSI 在涨
        if (slopePricePct < 0 && slopeRsi > 0) {
            double pricePart = Math.min(1.0, -slopePricePct / 1.0);
            double rsiPart   = Math.min(1.0, slopeRsi / 2.0);
            bullishPressure  = Math.round(Math.sqrt(pricePart * rsiPart) * 100.0);
        }

        return new double[]{bearishPressure, bullishPressure};
    }

    /**
     * 对 arr[start..end] 做最小二乘线性回归，返回斜率（y/bar）。
     * x 坐标为 0, 1, 2, …
     */
    private double linearSlope(double[] arr, int start, int end) {
        int len = end - start + 1;
        if (len < 2) return 0;

        double sumX = 0, sumY = 0, sumXY = 0, sumX2 = 0;
        for (int i = 0; i < len; i++) {
            double x = i;
            double y = arr[start + i];
            sumX  += x;
            sumY  += y;
            sumXY += x * y;
            sumX2 += x * x;
        }
        double denom = len * sumX2 - sumX * sumX;
        if (denom == 0) return 0;
        return (len * sumXY - sumX * sumY) / denom;
    }

    // ─── RSI 序列计算（Wilder 平滑法）─────────────────────────────────────────

    private double[] computeRsiSeries(double[] closes, int period) {
        double[] rsi = new double[closes.length];
        if (closes.length < period + 1) return rsi;

        double avgGain = 0, avgLoss = 0;
        for (int i = 1; i <= period; i++) {
            double change = closes[i] - closes[i - 1];
            if (change > 0) avgGain += change;
            else avgLoss += Math.abs(change);
        }
        avgGain /= period;
        avgLoss /= period;

        rsi[period] = avgLoss == 0 ? 100 : 100 - 100.0 / (1 + avgGain / avgLoss);

        for (int i = period + 1; i < closes.length; i++) {
            double change = closes[i] - closes[i - 1];
            if (change > 0) {
                avgGain = (avgGain * (period - 1) + change) / period;
                avgLoss = (avgLoss * (period - 1)) / period;
            } else {
                avgGain = (avgGain * (period - 1)) / period;
                avgLoss = (avgLoss * (period - 1) + Math.abs(change)) / period;
            }
            rsi[i] = avgLoss == 0 ? 100 : 100 - 100.0 / (1 + avgGain / avgLoss);
        }
        return rsi;
    }

    // ─── 辅助类 ────────────────────────────────────────────────────────────────

    private static class SwingPoint {
        final double price;
        final double rsi;

        SwingPoint(double price, double rsi) {
            this.price = price;
            this.rsi   = rsi;
        }
    }

    private IndicatorResult neutralResult() {
        Map<String, Double> values = new HashMap<>();
        values.put("bearishDivergence",        0.0);
        values.put("bullishDivergence",         0.0);
        values.put("bearishDivergenceForming",  0.0);
        values.put("bullishDivergenceForming",  0.0);
        values.put("bearishPressure",           0.0);
        values.put("bullishPressure",           0.0);
        return IndicatorResult.builder()
                .type(IndicatorType.DIVERGENCE)
                .values(values)
                .suggestion(SignalSuggestion.NEUTRAL)
                .build();
    }

    // ─── 工具方法 ──────────────────────────────────────────────────────────────

    private int getParam(Map<String, Object> params, String key, int defaultValue) {
        if (params == null) return defaultValue;
        Object val = params.get(key);
        return val instanceof Number ? ((Number) val).intValue() : defaultValue;
    }
}

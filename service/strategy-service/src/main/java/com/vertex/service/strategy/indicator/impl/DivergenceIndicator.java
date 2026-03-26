package com.vertex.service.strategy.indicator.impl;

import com.vertex.model.entity.quote.KLine;
import com.vertex.model.entity.strategy.IndicatorType;
import com.vertex.service.strategy.indicator.IndicatorResult;
import com.vertex.service.strategy.indicator.IndicatorResult.SignalSuggestion;
import com.vertex.service.strategy.indicator.TechnicalIndicator;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 背离指标 (Divergence)
 * <p>
 * 通过对比价格摆动高低点与 RSI 对应值，检测趋势与动量之间的背离：
 * <ul>
 *   <li><b>顶背离（Bearish Divergence）</b>：价格创新高，RSI 未创新高 → SELL<br>
 *       典型场景：上升趋势末期，价格继续上涨但多头动能已在衰竭</li>
 *   <li><b>底背离（Bullish Divergence）</b>：价格创新低，RSI 未创新低 → BUY<br>
 *       典型场景：下降趋势末期，价格继续下跌但空头动能已在收敛</li>
 * </ul>
 *
 * <h3>摆动点确认机制</h3>
 * 摆动高点：某K线的收盘价严格高于其左右各 swingStrength 根K线<br>
 * 摆动低点：某K线的收盘价严格低于其左右各 swingStrength 根K线<br>
 * 由于需要右侧确认，最新的 swingStrength 根K线不参与判断。
 *
 * <h3>参数</h3>
 * <ul>
 *   <li>{@code lookback}（默认 20）：向前搜索摆动点的K线窗口大小</li>
 *   <li>{@code rsiPeriod}（默认 14）：内部 RSI 计算周期</li>
 *   <li>{@code swingStrength}（默认 2）：摆动点左右各需确认的K线数</li>
 * </ul>
 *
 * <h3>输出值</h3>
 * <ul>
 *   <li>{@code bearishDivergence}：1.0 = 检测到顶背离，0.0 = 无</li>
 *   <li>{@code bullishDivergence}：1.0 = 检测到底背离，0.0 = 无</li>
 * </ul>
 *
 * <h3>典型出场配置（exitIndicatorConfigs）</h3>
 * <pre>
 * {
 *   "indicatorType": "DIVERGENCE",
 *   "params": {"lookback": 20, "rsiPeriod": 14, "swingStrength": 2},
 *   "weight": 100,
 *   "sellConditions": [{"field": "bearishDivergence", "op": "GTE", "threshold": 1.0}]
 * }
 * </pre>
 * </p>
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
        // RSI 预热 + 搜索窗口 + 两侧确认
        return rsiPeriod + lookback + swingStrength * 2 + 5;
    }

    @Override
    public IndicatorResult calculate(List<KLine> klines, Map<String, Object> params) {
        int lookback = getParam(params, "lookback", 20);
        int rsiPeriod = getParam(params, "rsiPeriod", 14);
        int swingStrength = getParam(params, "swingStrength", 2);

        int n = klines.size();
        double[] closes = new double[n];
        for (int i = 0; i < n; i++) {
            closes[i] = klines.get(i).getClose().doubleValue();
        }

        // Step 1: 计算完整 RSI 序列
        double[] rsiSeries = computeRsiSeries(closes, rsiPeriod);

        // Step 2: 确定有效搜索窗口
        // 最后 swingStrength 根K线无法确认（缺少右侧K线），从 confirmedEnd 向前 lookback 根
        int confirmedEnd = n - 1 - swingStrength;
        int confirmedStart = Math.max(rsiPeriod + swingStrength, confirmedEnd - lookback);

        if (confirmedEnd < confirmedStart) {
            // 数据不足，返回无背离
            return neutralResult();
        }

        // Step 3: 找出窗口内所有已确认的摆动高点和低点
        List<SwingPoint> swingHighs = new ArrayList<>();
        List<SwingPoint> swingLows = new ArrayList<>();

        for (int i = confirmedStart; i <= confirmedEnd; i++) {
            if (rsiSeries[i] <= 0) continue; // RSI 未预热
            if (isSwingHigh(closes, i, swingStrength)) {
                swingHighs.add(new SwingPoint(closes[i], rsiSeries[i]));
            }
            if (isSwingLow(closes, i, swingStrength)) {
                swingLows.add(new SwingPoint(closes[i], rsiSeries[i]));
            }
        }

        // Step 4: 检测背离（取最近两个摆动点比较）
        double bearishDivergence = 0.0;
        double bullishDivergence = 0.0;

        // 顶背离：价格更高高点，RSI 更低高点 → 多头动能衰竭
        if (swingHighs.size() >= 2) {
            SwingPoint prev = swingHighs.get(swingHighs.size() - 2);
            SwingPoint curr = swingHighs.get(swingHighs.size() - 1);
            if (curr.price > prev.price && curr.rsi < prev.rsi) {
                bearishDivergence = 1.0;
            }
        }

        // 底背离：价格更低低点，RSI 更高低点 → 空头动能收敛
        if (swingLows.size() >= 2) {
            SwingPoint prev = swingLows.get(swingLows.size() - 2);
            SwingPoint curr = swingLows.get(swingLows.size() - 1);
            if (curr.price < prev.price && curr.rsi > prev.rsi) {
                bullishDivergence = 1.0;
            }
        }

        // Step 5: 背离信号（向后兼容 suggestion；精确配置请使用 buyConditions/sellConditions）
        SignalSuggestion suggestion;
        if (bearishDivergence > 0) {
            suggestion = SignalSuggestion.SELL; // 顶背离 → 看空
        } else if (bullishDivergence > 0) {
            suggestion = SignalSuggestion.BUY;  // 底背离 → 看多
        } else {
            suggestion = SignalSuggestion.NEUTRAL;
        }

        return IndicatorResult.builder()
                .type(IndicatorType.DIVERGENCE)
                .values(Map.of(
                        "bearishDivergence", bearishDivergence,
                        "bullishDivergence", bullishDivergence
                ))
                .suggestion(suggestion)
                .build();
    }

    // ─── 摆动点判断 ────────────────────────────────────────────

    /**
     * 摆动高点：index 处的价格严格高于左右各 strength 根K线
     */
    private boolean isSwingHigh(double[] prices, int index, int strength) {
        for (int j = 1; j <= strength; j++) {
            if (index - j < 0 || index + j >= prices.length) return false;
            if (prices[index - j] >= prices[index] || prices[index + j] >= prices[index]) return false;
        }
        return true;
    }

    /**
     * 摆动低点：index 处的价格严格低于左右各 strength 根K线
     */
    private boolean isSwingLow(double[] prices, int index, int strength) {
        for (int j = 1; j <= strength; j++) {
            if (index - j < 0 || index + j >= prices.length) return false;
            if (prices[index - j] <= prices[index] || prices[index + j] <= prices[index]) return false;
        }
        return true;
    }

    // ─── RSI 序列计算（Wilder 平滑法）────────────────────────

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

    // ─── 辅助类 ────────────────────────────────────────────────

    private static class SwingPoint {
        final double price;
        final double rsi;

        SwingPoint(double price, double rsi) {
            this.price = price;
            this.rsi = rsi;
        }
    }

    private IndicatorResult neutralResult() {
        return IndicatorResult.builder()
                .type(IndicatorType.DIVERGENCE)
                .values(Map.of(
                        "bearishDivergence", 0.0,
                        "bullishDivergence", 0.0
                ))
                .suggestion(SignalSuggestion.NEUTRAL)
                .build();
    }

    // ─── 工具方法 ──────────────────────────────────────────────

    private int getParam(Map<String, Object> params, String key, int defaultValue) {
        if (params == null) return defaultValue;
        Object val = params.get(key);
        return val instanceof Number ? ((Number) val).intValue() : defaultValue;
    }
}

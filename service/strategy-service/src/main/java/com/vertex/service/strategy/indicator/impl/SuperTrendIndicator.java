package com.vertex.service.strategy.indicator.impl;

import com.vertex.model.entity.quote.KLine;
import com.vertex.model.entity.strategy.IndicatorType;
import com.vertex.service.strategy.indicator.IndicatorResult;
import com.vertex.service.strategy.indicator.TechnicalIndicator;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 超级趋势指标 (SuperTrend)
 * <p>
 * 基于 ATR 的波动率自适应趋势跟踪指标，在加密货币市场极为流行。
 * 利用 ATR 动态调整趋势带宽度，波动大时带宽，波动小时带窄，
 * 减少震荡市中的假信号。
 * <br>
 * 步骤:
 * 1. 计算 ATR (Wilder 平滑)
 * 2. 基础上下带 = HL2 ± multiplier × ATR
 * 3. 最终带的棘轮机制：带只向趋势方向移动
 * 4. 价格突破带时趋势翻转
 * <br>
 * 参数: period(10), multiplier(3.0)
 * </p>
 */
@Component
public class SuperTrendIndicator implements TechnicalIndicator {

    @Override
    public IndicatorType type() {
        return IndicatorType.SUPERTREND;
    }

    @Override
    public int requiredDataPoints(Map<String, Object> params) {
        int period = getParam(params, "period", 10);
        return period + 15;
    }

    @Override
    public IndicatorResult calculate(List<KLine> klines, Map<String, Object> params) {
        int period = getParam(params, "period", 10);
        double multiplier = getDoubleParam(params, "multiplier", 3.0);

        int n = klines.size();
        double[] highs = new double[n];
        double[] lows = new double[n];
        double[] closes = new double[n];

        for (int i = 0; i < n; i++) {
            highs[i] = klines.get(i).getHigh().doubleValue();
            lows[i] = klines.get(i).getLow().doubleValue();
            closes[i] = klines.get(i).getClose().doubleValue();
        }

        // Step 1: 计算 ATR (Wilder 平滑)
        double[] atr = new double[n];
        double[] tr = new double[n];

        tr[0] = highs[0] - lows[0];
        for (int i = 1; i < n; i++) {
            tr[i] = Math.max(
                    highs[i] - lows[i],
                    Math.max(
                            Math.abs(highs[i] - closes[i - 1]),
                            Math.abs(lows[i] - closes[i - 1])
                    )
            );
        }

        // 初始 ATR = 前 period 个 TR 的平均
        double sum = 0;
        for (int i = 0; i < period && i < n; i++) {
            sum += tr[i];
        }
        atr[period - 1] = sum / period;

        for (int i = period; i < n; i++) {
            atr[i] = (atr[i - 1] * (period - 1) + tr[i]) / period;
        }

        // Step 2: 计算 SuperTrend
        double[] finalUpperBand = new double[n];
        double[] finalLowerBand = new double[n];
        boolean[] trendUp = new boolean[n];

        // 初始化第一个有效位置
        int start = period - 1;
        double hl2 = (highs[start] + lows[start]) / 2.0;
        finalUpperBand[start] = hl2 + multiplier * atr[start];
        finalLowerBand[start] = hl2 - multiplier * atr[start];
        trendUp[start] = true; // 假设初始上升趋势

        for (int i = start + 1; i < n; i++) {
            hl2 = (highs[i] + lows[i]) / 2.0;
            double basicUpperBand = hl2 + multiplier * atr[i];
            double basicLowerBand = hl2 - multiplier * atr[i];

            // 棘轮机制：最终上轨只能下移或在价格突破时重置
            if (basicUpperBand < finalUpperBand[i - 1] || closes[i - 1] > finalUpperBand[i - 1]) {
                finalUpperBand[i] = basicUpperBand;
            } else {
                finalUpperBand[i] = finalUpperBand[i - 1];
            }

            // 棘轮机制：最终下轨只能上移或在价格突破时重置
            if (basicLowerBand > finalLowerBand[i - 1] || closes[i - 1] < finalLowerBand[i - 1]) {
                finalLowerBand[i] = basicLowerBand;
            } else {
                finalLowerBand[i] = finalLowerBand[i - 1];
            }

            // 确定趋势方向
            if (trendUp[i - 1]) {
                // 前一根是上升趋势
                if (closes[i] < finalLowerBand[i]) {
                    trendUp[i] = false; // 翻转为下降
                } else {
                    trendUp[i] = true;  // 维持上升
                }
            } else {
                // 前一根是下降趋势
                if (closes[i] > finalUpperBand[i]) {
                    trendUp[i] = true;  // 翻转为上升
                } else {
                    trendUp[i] = false; // 维持下降
                }
            }
        }

        // 取最后的值
        int last = n - 1;
        boolean currentTrendUp = trendUp[last];
        boolean prevTrendUp = last > start ? trendUp[last - 1] : currentTrendUp;
        double superTrend = currentTrendUp ? finalLowerBand[last] : finalUpperBand[last];

        return IndicatorResult.builder()
                .type(IndicatorType.SUPERTREND)
                .values(Map.of(
                        "superTrend", round(superTrend),
                        "trend", currentTrendUp ? 1.0 : -1.0,
                        "prevTrend", prevTrendUp ? 1.0 : -1.0,
                        "upperBand", round(finalUpperBand[last]),
                        "lowerBand", round(finalLowerBand[last])
                ))
                .build();
    }

    private int getParam(Map<String, Object> params, String key, int defaultValue) {
        Object val = params.get(key);
        return val instanceof Number ? ((Number) val).intValue() : defaultValue;
    }

    private double getDoubleParam(Map<String, Object> params, String key, double defaultValue) {
        Object val = params.get(key);
        return val instanceof Number ? ((Number) val).doubleValue() : defaultValue;
    }

    private double round(double val) {
        return Math.round(val * 100000.0) / 100000.0;
    }
}

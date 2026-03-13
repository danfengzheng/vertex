package com.vertex.service.strategy.indicator.impl;

import com.vertex.model.entity.quote.KLine;
import com.vertex.model.entity.strategy.IndicatorType;
import com.vertex.service.strategy.indicator.IndicatorResult;

import com.vertex.service.strategy.indicator.TechnicalIndicator;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 抛物线转向指标 (Parabolic SAR)
 * <p>
 * 经典趋势跟踪与止损指标。SAR 点位始终在价格的一侧，
 * 当价格穿越 SAR 时发生趋势反转，产生交易信号。
 * <br>
 * 参数: afStart(0.02), afStep(0.02), afMax(0.2)
 * </p>
 */
@Component
public class SarIndicator implements TechnicalIndicator {

    @Override
    public IndicatorType type() {
        return IndicatorType.SAR;
    }

    @Override
    public int requiredDataPoints(Map<String, Object> params) {
        return 5;
    }

    @Override
    public IndicatorResult calculate(List<KLine> klines, Map<String, Object> params) {
        double afStart = getDoubleParam(params, "afStart", 0.02);
        double afStep = getDoubleParam(params, "afStep", 0.02);
        double afMax = getDoubleParam(params, "afMax", 0.2);

        int n = klines.size();
        double[] highs = new double[n];
        double[] lows = new double[n];
        for (int i = 0; i < n; i++) {
            highs[i] = klines.get(i).getHigh().doubleValue();
            lows[i] = klines.get(i).getLow().doubleValue();
        }

        // 初始化：假设第一根K线是上升趋势
        boolean trendUp = highs[1] >= highs[0]; // 用前两根判断初始趋势
        double sar = trendUp ? lows[0] : highs[0];
        double ep = trendUp ? highs[0] : lows[0]; // 极值点
        double af = afStart;

        boolean prevTrendUp = trendUp;
        double currentSar = sar;

        for (int i = 1; i < n; i++) {
            prevTrendUp = trendUp;

            // 计算新的 SAR
            currentSar = sar + af * (ep - sar);

            if (trendUp) {
                // 上升趋势中，SAR 不能高于前两根K线的最低价
                if (i >= 2) {
                    currentSar = Math.min(currentSar, Math.min(lows[i - 1], lows[i - 2]));
                } else {
                    currentSar = Math.min(currentSar, lows[i - 1]);
                }

                // 检查是否反转
                if (lows[i] < currentSar) {
                    // 反转为下降趋势
                    trendUp = false;
                    currentSar = ep; // SAR 设为之前的极值点
                    ep = lows[i];
                    af = afStart;
                } else {
                    // 继续上升趋势，更新极值点
                    if (highs[i] > ep) {
                        ep = highs[i];
                        af = Math.min(af + afStep, afMax);
                    }
                }
            } else {
                // 下降趋势中，SAR 不能低于前两根K线的最高价
                if (i >= 2) {
                    currentSar = Math.max(currentSar, Math.max(highs[i - 1], highs[i - 2]));
                } else {
                    currentSar = Math.max(currentSar, highs[i - 1]);
                }

                // 检查是否反转
                if (highs[i] > currentSar) {
                    // 反转为上升趋势
                    trendUp = true;
                    currentSar = ep; // SAR 设为之前的极值点
                    ep = highs[i];
                    af = afStart;
                } else {
                    // 继续下降趋势，更新极值点
                    if (lows[i] < ep) {
                        ep = lows[i];
                        af = Math.min(af + afStep, afMax);
                    }
                }
            }

            sar = currentSar;
        }

        return IndicatorResult.builder()
                .type(IndicatorType.SAR)
                .values(Map.of(
                        "sar", round(currentSar),
                        "trend", trendUp ? 1.0 : -1.0
                ))
                .build();
    }

    private double getDoubleParam(Map<String, Object> params, String key, double defaultValue) {
        Object val = params.get(key);
        return val instanceof Number ? ((Number) val).doubleValue() : defaultValue;
    }

    private double round(double val) {
        return Math.round(val * 100000.0) / 100000.0;
    }
}

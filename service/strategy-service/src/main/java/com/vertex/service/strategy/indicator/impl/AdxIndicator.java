package com.vertex.service.strategy.indicator.impl;

import com.vertex.model.entity.quote.KLine;
import com.vertex.model.entity.strategy.IndicatorType;
import com.vertex.service.strategy.indicator.IndicatorResult;
import com.vertex.service.strategy.indicator.IndicatorResult.SignalSuggestion;
import com.vertex.service.strategy.indicator.TechnicalIndicator;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 平均趋向指数 (ADX - Average Directional Index)
 * <p>
 * 衡量趋势强度的核心指标，ADX 值越高表示趋势越强。
 * 同时包含 +DI/-DI 方向指标判断趋势方向。
 * <br>
 * 步骤:
 * 1. 计算 +DM/-DM (方向运动)
 * 2. 计算 TR (真实波幅)
 * 3. Wilder 平滑得到 +DI/-DI
 * 4. 计算 DX = |+DI - -DI| / (+DI + -DI) × 100
 * 5. 平滑 DX 得到 ADX
 * <br>
 * 参数: period(14), trendThreshold(25)
 * </p>
 */
@Component
public class AdxIndicator implements TechnicalIndicator {

    @Override
    public IndicatorType type() {
        return IndicatorType.ADX;
    }

    @Override
    public int requiredDataPoints(Map<String, Object> params) {
        int period = getParam(params, "period", 14);
        return period * 3; // DI 平滑 + DX 计算 + ADX 平滑
    }

    @Override
    public IndicatorResult calculate(List<KLine> klines, Map<String, Object> params) {
        int period = getParam(params, "period", 14);
        int trendThreshold = getParam(params, "trendThreshold", 25);

        int n = klines.size();
        double[] highs = new double[n];
        double[] lows = new double[n];
        double[] closes = new double[n];

        for (int i = 0; i < n; i++) {
            highs[i] = klines.get(i).getHigh().doubleValue();
            lows[i] = klines.get(i).getLow().doubleValue();
            closes[i] = klines.get(i).getClose().doubleValue();
        }

        // Step 1: 计算 +DM, -DM, TR 序列
        double[] plusDm = new double[n];
        double[] minusDm = new double[n];
        double[] tr = new double[n];

        for (int i = 1; i < n; i++) {
            double upMove = highs[i] - highs[i - 1];
            double downMove = lows[i - 1] - lows[i];

            plusDm[i] = (upMove > downMove && upMove > 0) ? upMove : 0;
            minusDm[i] = (downMove > upMove && downMove > 0) ? downMove : 0;

            tr[i] = Math.max(
                    highs[i] - lows[i],
                    Math.max(
                            Math.abs(highs[i] - closes[i - 1]),
                            Math.abs(lows[i] - closes[i - 1])
                    )
            );
        }

        // Step 2: Wilder 平滑 +DM, -DM, TR (初始 = 前 period 个的累加)
        double smoothPlusDm = 0, smoothMinusDm = 0, smoothTr = 0;
        for (int i = 1; i <= period; i++) {
            smoothPlusDm += plusDm[i];
            smoothMinusDm += minusDm[i];
            smoothTr += tr[i];
        }

        // Step 3: 计算 +DI, -DI, DX 序列
        double[] dxSeries = new double[n];
        int dxCount = 0;
        double plusDi = 0, minusDi = 0;

        // 第一个 DI 值
        if (smoothTr > 0) {
            plusDi = smoothPlusDm / smoothTr * 100;
            minusDi = smoothMinusDm / smoothTr * 100;
        }
        double diSum = plusDi + minusDi;
        dxSeries[period] = diSum > 0 ? Math.abs(plusDi - minusDi) / diSum * 100 : 0;
        dxCount++;

        // 后续 Wilder 平滑
        for (int i = period + 1; i < n; i++) {
            smoothPlusDm = smoothPlusDm - smoothPlusDm / period + plusDm[i];
            smoothMinusDm = smoothMinusDm - smoothMinusDm / period + minusDm[i];
            smoothTr = smoothTr - smoothTr / period + tr[i];

            if (smoothTr > 0) {
                plusDi = smoothPlusDm / smoothTr * 100;
                minusDi = smoothMinusDm / smoothTr * 100;
            }

            diSum = plusDi + minusDi;
            dxSeries[i] = diSum > 0 ? Math.abs(plusDi - minusDi) / diSum * 100 : 0;
            dxCount++;
        }

        // Step 4: 计算 ADX (对 DX 序列做 Wilder 平滑)
        double adx = 0;
        int adxStart = period + period; // ADX 从第 2*period 个位置开始

        if (adxStart < n) {
            // 初始 ADX = 前 period 个 DX 的平均值
            double dxSum = 0;
            for (int i = period; i < adxStart; i++) {
                dxSum += dxSeries[i];
            }
            adx = dxSum / period;

            // 后续 Wilder 平滑
            for (int i = adxStart; i < n; i++) {
                adx = (adx * (period - 1) + dxSeries[i]) / period;
            }
        }

        // 信号判断（向后兼容：无 buyConditions/sellConditions 时使用）
        SignalSuggestion suggestion;
        if (adx > trendThreshold && plusDi > minusDi) {
            suggestion = SignalSuggestion.BUY;   // 强多头趋势
        } else if (adx > trendThreshold && minusDi > plusDi) {
            suggestion = SignalSuggestion.SELL;  // 强空头趋势
        } else {
            suggestion = SignalSuggestion.NEUTRAL; // 趋势不够强
        }

        return IndicatorResult.builder()
                .type(IndicatorType.ADX)
                .values(Map.of(
                        "adx", round(adx),
                        "plusDi", round(plusDi),
                        "minusDi", round(minusDi)
                ))
                .suggestion(suggestion)
                .build();
    }

    private int getParam(Map<String, Object> params, String key, int defaultValue) {
        Object val = params.get(key);
        return val instanceof Number ? ((Number) val).intValue() : defaultValue;
    }

    private double round(double val) {
        return Math.round(val * 100.0) / 100.0;
    }
}

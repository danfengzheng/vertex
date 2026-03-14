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
 * 能量潮指标 (On-Balance Volume, OBV)
 * <p>
 * 通过累积涨跌K线的成交量来追踪资金流向：
 * 上涨K线: OBV += Volume（资金流入），下跌K线: OBV -= Volume（资金流出），平盘K线: OBV 不变。
 * OBV 信号线 = OBV 的 SMA(signalPeriod)。
 * 适用场景: 量价背离检测 — 价格新高但 OBV 未新高 = 顶背离（看空）
 * </p>
 */
@Component
public class ObvIndicator implements TechnicalIndicator {

    @Override
    public IndicatorType type() {
        return IndicatorType.OBV;
    }

    @Override
    public int requiredDataPoints(Map<String, Object> params) {
        int signalPeriod = getIntParam(params, "signalPeriod", 10);
        return signalPeriod + 10;
    }

    @Override
    public IndicatorResult calculate(List<KLine> klines, Map<String, Object> params) {
        int signalPeriod = getIntParam(params, "signalPeriod", 10);
        int size = klines.size();

        // Step 1: 累积计算 OBV 序列
        double[] obvSeries = new double[size];
        obvSeries[0] = 0;

        for (int i = 1; i < size; i++) {
            double close = klines.get(i).getClose().doubleValue();
            double prevClose = klines.get(i - 1).getClose().doubleValue();
            double volume = klines.get(i).getVolume().doubleValue();

            if (close > prevClose) {
                obvSeries[i] = obvSeries[i - 1] + volume;  // 上涨 → 资金流入
            } else if (close < prevClose) {
                obvSeries[i] = obvSeries[i - 1] - volume;  // 下跌 → 资金流出
            } else {
                obvSeries[i] = obvSeries[i - 1];           // 平盘 → 不变
            }
        }

        double obv = obvSeries[size - 1];

        // Step 2: 计算 OBV 信号线 (SMA of OBV)
        double obvSignal = 0;
        int signalStart = Math.max(0, size - signalPeriod);
        int signalCount = size - signalStart;
        for (int i = signalStart; i < size; i++) {
            obvSignal += obvSeries[i];
        }
        obvSignal = signalCount > 0 ? obvSignal / signalCount : 0;

        // 信号判断（向后兼容：无 buyConditions/sellConditions 时使用）
        double diff = obvSignal != 0 ? (obv - obvSignal) / Math.abs(obvSignal) * 100 : 0;
        SignalSuggestion suggestion;
        if (diff > 1.0) {
            suggestion = SignalSuggestion.BUY;   // OBV 显著高于信号线 → 资金净流入
        } else if (diff < -1.0) {
            suggestion = SignalSuggestion.SELL;  // OBV 显著低于信号线 → 资金净流出
        } else {
            suggestion = SignalSuggestion.NEUTRAL; // OBV ≈ 信号线 → 方向不明
        }

        return IndicatorResult.builder()
                .type(IndicatorType.OBV)
                .values(Map.of(
                        "obv", round(obv),
                        "obvSignal", round(obvSignal)
                ))
                .suggestion(suggestion)
                .build();
    }

    private int getIntParam(Map<String, Object> params, String key, int defaultValue) {
        if (params == null) return defaultValue;
        Object val = params.get(key);
        return val instanceof Number ? ((Number) val).intValue() : defaultValue;
    }

    private double round(double val) {
        return Math.round(val * 100.0) / 100.0;
    }
}

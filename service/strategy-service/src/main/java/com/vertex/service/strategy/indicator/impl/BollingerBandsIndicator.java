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
 * 布林带指标 (Bollinger Bands)
 * <p>
 * 参数: period (默认 20), multiplier (默认 2.0)
 * 公式:
 *   中轨 = SMA(period)
 *   上轨 = SMA + multiplier * StdDev
 *   下轨 = SMA - multiplier * StdDev
 * 信号: 价格 < 下轨 → BUY, 价格 > 上轨 → SELL, 否则 NEUTRAL
 * </p>
 */
@Component
public class BollingerBandsIndicator implements TechnicalIndicator {

    @Override
    public IndicatorType type() {
        return IndicatorType.BOLL;
    }

    @Override
    public int requiredDataPoints(Map<String, Object> params) {
        return getParam(params, "period", 20);
    }

    @Override
    public IndicatorResult calculate(List<KLine> klines, Map<String, Object> params) {
        int period = getParam(params, "period", 20);
        double multiplier = getDoubleParam(params, "multiplier", 2.0);

        // 取最近 period 根K线计算 SMA（中轨）
        List<KLine> window = klines.subList(klines.size() - period, klines.size());
        double sma = window.stream()
                .mapToDouble(k -> k.getClose().doubleValue())
                .average()
                .orElse(0);

        // 计算标准差
        double variance = window.stream()
                .mapToDouble(k -> {
                    double diff = k.getClose().doubleValue() - sma;
                    return diff * diff;
                })
                .average()
                .orElse(0);
        double stdDev = Math.sqrt(variance);

        double upperBand = sma + multiplier * stdDev;
        double lowerBand = sma - multiplier * stdDev;
        double currentClose = klines.get(klines.size() - 1).getClose().doubleValue();

        // 信号判断：价格跌破下轨 → 超卖买入，突破上轨 → 超买卖出
        SignalSuggestion suggestion;
        if (currentClose < lowerBand) {
            suggestion = SignalSuggestion.BUY;
        } else if (currentClose > upperBand) {
            suggestion = SignalSuggestion.SELL;
        } else {
            suggestion = SignalSuggestion.NEUTRAL;
        }

        return IndicatorResult.builder()
                .type(IndicatorType.BOLL)
                .values(Map.of(
                        "upper", round(upperBand),
                        "middle", round(sma),
                        "lower", round(lowerBand),
                        "stdDev", round(stdDev)
                ))
                .suggestion(suggestion)
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

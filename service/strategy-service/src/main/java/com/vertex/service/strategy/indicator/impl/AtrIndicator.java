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
 * 平均真实波幅 (Average True Range)
 * <p>
 * 参数: period (默认 14)
 * 公式:
 *   TR = max(High - Low, |High - prevClose|, |Low - prevClose|)
 *   ATR = Wilder 平滑 (prevATR * (period-1) + TR) / period
 * 信号: 始终 NEUTRAL（ATR 是波动率指标，非方向性）
 * </p>
 */
@Component
public class AtrIndicator implements TechnicalIndicator {

    @Override
    public IndicatorType type() {
        return IndicatorType.ATR;
    }

    @Override
    public int requiredDataPoints(Map<String, Object> params) {
        return getParam(params, "period", 14) + 1;
    }

    @Override
    public IndicatorResult calculate(List<KLine> klines, Map<String, Object> params) {
        int period = getParam(params, "period", 14);

        // 计算每根K线的 True Range
        double[] trueRanges = new double[klines.size() - 1];
        for (int i = 1; i < klines.size(); i++) {
            KLine current = klines.get(i);
            KLine previous = klines.get(i - 1);

            double highLow = current.getHigh().doubleValue() - current.getLow().doubleValue();
            double highClose = Math.abs(current.getHigh().doubleValue() - previous.getClose().doubleValue());
            double lowClose = Math.abs(current.getLow().doubleValue() - previous.getClose().doubleValue());

            trueRanges[i - 1] = Math.max(highLow, Math.max(highClose, lowClose));
        }

        // 使用 Wilder 平滑法计算 ATR
        double atr = 0;
        int calcPeriod = Math.min(period, trueRanges.length);
        for (int i = 0; i < calcPeriod; i++) {
            atr += trueRanges[i];
        }
        atr /= calcPeriod;

        for (int i = calcPeriod; i < trueRanges.length; i++) {
            atr = (atr * (period - 1) + trueRanges[i]) / period;
        }

        // 计算 ATR 占当前价格的百分比
        double currentPrice = klines.get(klines.size() - 1).getClose().doubleValue();
        double atrPercent = currentPrice > 0 ? (atr / currentPrice) * 100 : 0;

        return IndicatorResult.builder()
                .type(IndicatorType.ATR)
                .values(Map.of(
                        "atr", round(atr),
                        "atrPercent", round(atrPercent)
                ))
                .suggestion(SignalSuggestion.NEUTRAL) // ATR 非方向性指标
                .build();
    }

    private int getParam(Map<String, Object> params, String key, int defaultValue) {
        Object val = params.get(key);
        return val instanceof Number ? ((Number) val).intValue() : defaultValue;
    }

    private double round(double val) {
        return Math.round(val * 100000.0) / 100000.0;
    }
}

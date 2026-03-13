package com.vertex.service.strategy.indicator.impl;

import com.vertex.model.entity.quote.KLine;
import com.vertex.model.entity.strategy.IndicatorType;
import com.vertex.service.strategy.indicator.IndicatorResult;
import com.vertex.service.strategy.indicator.TechnicalIndicator;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 相对强弱指标 (RSI)
 * <p>
 * 参数: period (默认 14)
 * 公式: RSI = 100 - 100/(1 + RS), RS = avgGain/avgLoss
 * </p>
 */
@Component
public class RelativeStrengthIndex implements TechnicalIndicator {

    @Override
    public IndicatorType type() {
        return IndicatorType.RSI;
    }

    @Override
    public int requiredDataPoints(Map<String, Object> params) {
        return getParam(params, "period", 14) + 1;
    }

    @Override
    public IndicatorResult calculate(List<KLine> klines, Map<String, Object> params) {
        int period = getParam(params, "period", 14);

        // 计算价格变动
        double[] changes = new double[klines.size() - 1];
        for (int i = 1; i < klines.size(); i++) {
            changes[i - 1] = klines.get(i).getClose().doubleValue()
                    - klines.get(i - 1).getClose().doubleValue();
        }

        // 初始平均涨幅和跌幅
        double avgGain = 0;
        double avgLoss = 0;
        for (int i = 0; i < period && i < changes.length; i++) {
            if (changes[i] > 0) {
                avgGain += changes[i];
            } else {
                avgLoss += Math.abs(changes[i]);
            }
        }
        avgGain /= period;
        avgLoss /= period;

        // Wilder 平滑法（exponential moving average 变体）
        for (int i = period; i < changes.length; i++) {
            double change = changes[i];
            if (change > 0) {
                avgGain = (avgGain * (period - 1) + change) / period;
                avgLoss = (avgLoss * (period - 1)) / period;
            } else {
                avgGain = (avgGain * (period - 1)) / period;
                avgLoss = (avgLoss * (period - 1) + Math.abs(change)) / period;
            }
        }

        double rsi;
        if (avgLoss == 0) {
            rsi = 100;
        } else {
            double rs = avgGain / avgLoss;
            rsi = 100 - 100.0 / (1 + rs);
        }

        return IndicatorResult.builder()
                .type(IndicatorType.RSI)
                .values(Map.of("rsi" + period, round(rsi)))
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

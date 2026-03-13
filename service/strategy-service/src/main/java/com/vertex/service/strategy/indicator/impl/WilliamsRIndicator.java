package com.vertex.service.strategy.indicator.impl;

import com.vertex.model.entity.quote.KLine;
import com.vertex.model.entity.strategy.IndicatorType;
import com.vertex.service.strategy.indicator.IndicatorResult;

import com.vertex.service.strategy.indicator.TechnicalIndicator;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 威廉指标 (Williams %R)
 * <p>
 * 超灵敏的超买超卖指标，非常适合短线高频交易的快速进出。
 * <br>
 * 公式: %R = (最高价N - 收盘价) / (最高价N - 最低价N) × (-100)
 * <br>
 * 取值范围: -100 ~ 0
 * <br>
 * 参数: period (默认 14)
 * </p>
 */
@Component
public class WilliamsRIndicator implements TechnicalIndicator {

    @Override
    public IndicatorType type() {
        return IndicatorType.WR;
    }

    @Override
    public int requiredDataPoints(Map<String, Object> params) {
        return getParam(params, "period", 14);
    }

    @Override
    public IndicatorResult calculate(List<KLine> klines, Map<String, Object> params) {
        int period = getParam(params, "period", 14);

        // 取最近 period 根K线
        int start = Math.max(0, klines.size() - period);
        List<KLine> window = klines.subList(start, klines.size());

        double highestHigh = Double.MIN_VALUE;
        double lowestLow = Double.MAX_VALUE;
        for (KLine k : window) {
            double high = k.getHigh().doubleValue();
            double low = k.getLow().doubleValue();
            if (high > highestHigh) highestHigh = high;
            if (low < lowestLow) lowestLow = low;
        }

        double currentClose = klines.get(klines.size() - 1).getClose().doubleValue();
        double range = highestHigh - lowestLow;

        // %R = (最高价 - 收盘价) / (最高价 - 最低价) × (-100)
        double wr = range > 0 ? (highestHigh - currentClose) / range * (-100) : -50;

        return IndicatorResult.builder()
                .type(IndicatorType.WR)
                .values(Map.of("wr" + period, round(wr)))
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

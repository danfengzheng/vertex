package com.vertex.service.strategy.indicator.impl;

import com.vertex.model.entity.quote.KLine;
import com.vertex.model.entity.strategy.IndicatorType;
import com.vertex.service.strategy.indicator.IndicatorResult;
import com.vertex.service.strategy.indicator.TechnicalIndicator;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 简单移动平均线 (SMA)
 * <p>
 * 参数: period (默认 20)
 * </p>
 */
@Component
public class SimpleMovingAverage implements TechnicalIndicator {

    @Override
    public IndicatorType type() {
        return IndicatorType.MA;
    }

    @Override
    public int requiredDataPoints(Map<String, Object> params) {
        return getParam(params, "period", 20);
    }

    @Override
    public IndicatorResult calculate(List<KLine> klines, Map<String, Object> params) {
        int period = getParam(params, "period", 20);

        // 取最近 period 根K线计算 SMA
        List<KLine> window = klines.subList(klines.size() - period, klines.size());
        double sma = window.stream()
                .mapToDouble(k -> k.getClose().doubleValue())
                .average()
                .orElse(0);

        return IndicatorResult.builder()
                .type(IndicatorType.MA)
                .values(Map.of("ma" + period, round(sma)))
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

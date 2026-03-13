package com.vertex.service.strategy.indicator.impl;

import com.vertex.model.entity.quote.KLine;
import com.vertex.model.entity.strategy.IndicatorType;
import com.vertex.service.strategy.indicator.IndicatorResult;

import com.vertex.service.strategy.indicator.TechnicalIndicator;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 指数移动平均线 (EMA)
 * <p>
 * 参数: period (默认 20)
 * 公式: EMA = 前一EMA + α * (close - 前一EMA), α = 2/(period+1)
 * </p>
 */
@Component
public class ExponentialMovingAverage implements TechnicalIndicator {

    @Override
    public IndicatorType type() {
        return IndicatorType.EMA;
    }

    @Override
    public int requiredDataPoints(Map<String, Object> params) {
        return getParam(params, "period", 20) * 2; // 需要更多数据以稳定 EMA
    }

    @Override
    public IndicatorResult calculate(List<KLine> klines, Map<String, Object> params) {
        int period = getParam(params, "period", 20);
        double multiplier = 2.0 / (period + 1);

        // 以前 period 根K线的 SMA 作为初始 EMA
        double ema = klines.subList(0, period).stream()
                .mapToDouble(k -> k.getClose().doubleValue())
                .average()
                .orElse(0);

        // 从 period 位置开始迭代计算 EMA
        for (int i = period; i < klines.size(); i++) {
            double close = klines.get(i).getClose().doubleValue();
            ema = close * multiplier + ema * (1 - multiplier);
        }

        return IndicatorResult.builder()
                .type(IndicatorType.EMA)
                .values(Map.of("ema" + period, round(ema)))
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

package com.vertex.service.strategy.indicator.impl;

import com.vertex.model.entity.quote.KLine;
import com.vertex.model.entity.strategy.IndicatorType;
import com.vertex.service.strategy.indicator.IndicatorResult;
import com.vertex.service.strategy.indicator.TechnicalIndicator;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * KDJ 随机指标 (Stochastic Oscillator)
 * <p>
 * 参数: rsvPeriod (默认 9), kPeriod (默认 3), dPeriod (默认 3)
 * 公式:
 *   RSV = (Close - Low_N) / (High_N - Low_N) * 100
 *   K = 2/3 * prevK + 1/3 * RSV
 *   D = 2/3 * prevD + 1/3 * K
 *   J = 3K - 2D
 * </p>
 */
@Component
public class KdjIndicator implements TechnicalIndicator {

    @Override
    public IndicatorType type() {
        return IndicatorType.KDJ;
    }

    @Override
    public int requiredDataPoints(Map<String, Object> params) {
        int rsvPeriod = getParam(params, "rsvPeriod", 9);
        return rsvPeriod + 10; // 额外数据用于 K/D 平滑稳定
    }

    @Override
    public IndicatorResult calculate(List<KLine> klines, Map<String, Object> params) {
        int rsvPeriod = getParam(params, "rsvPeriod", 9);

        // 计算每根K线的 RSV
        double k = 50.0, prevK = 50.0;
        double d = 50.0, prevD = 50.0;

        for (int i = rsvPeriod - 1; i < klines.size(); i++) {
            List<KLine> window = klines.subList(i - rsvPeriod + 1, i + 1);
            double high = window.stream().mapToDouble(kl -> kl.getHigh().doubleValue()).max().orElse(0);
            double low = window.stream().mapToDouble(kl -> kl.getLow().doubleValue()).min().orElse(0);
            double close = klines.get(i).getClose().doubleValue();

            double rsv = (high == low) ? 50.0 : ((close - low) / (high - low)) * 100;

            prevK = k;
            prevD = d;
            k = (2.0 / 3.0) * prevK + (1.0 / 3.0) * rsv;
            d = (2.0 / 3.0) * prevD + (1.0 / 3.0) * k;
        }

        double j = 3 * k - 2 * d;

        return IndicatorResult.builder()
                .type(IndicatorType.KDJ)
                .values(Map.of(
                        "k", round(k),
                        "d", round(d),
                        "j", round(j),
                        "kPrev", round(prevK),
                        "dPrev", round(prevD)
                ))
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

package com.vertex.service.strategy.indicator.impl;

import com.vertex.model.entity.quote.KLine;
import com.vertex.model.entity.strategy.IndicatorType;
import com.vertex.service.strategy.indicator.IndicatorResult;

import com.vertex.service.strategy.indicator.TechnicalIndicator;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 成交量加权平均价格 (VWAP) — 突破确认模式
 * <p>
 * 公式: VWAP = Σ(典型价格 × 成交量) / Σ(成交量)，典型价格 = (最高 + 最低 + 收盘) / 3
 * </p>
 *
 * <h3>参数</h3>
 * <ul>
 *   <li>{@code deviationPct}（默认 0.2）：有效突破的偏离区间上限（%）。
 *       偏离超过此值视为"强弩之末"，不追入。</li>
 * </ul>
 *
 */
@Component
public class VwapIndicator implements TechnicalIndicator {

    @Override
    public IndicatorType type() {
        return IndicatorType.VWAP;
    }

    @Override
    public int requiredDataPoints(Map<String, Object> params) {
        // VWAP 至少需要 20 根K线来计算有意义的值
        return 20;
    }

    @Override
    public IndicatorResult calculate(List<KLine> klines, Map<String, Object> params) {
        // 有效突破偏离阈值（%），默认 0.2%，可通过 params 配置
        double deviationPct = getParam(params, "deviationPct", 0.2);

        double cumulativeTPV = 0; // Σ(典型价格 × 成交量)
        double cumulativeVol = 0; // Σ(成交量)

        for (KLine k : klines) {
            double high   = k.getHigh().doubleValue();
            double low    = k.getLow().doubleValue();
            double close  = k.getClose().doubleValue();
            double volume = k.getVolume().doubleValue();

            double typicalPrice = (high + low + close) / 3.0;
            cumulativeTPV += typicalPrice * volume;
            cumulativeVol += volume;
        }

        // 防止除零
        double vwap = cumulativeVol > 0 ? cumulativeTPV / cumulativeVol : 0;

        double currentClose = klines.get(klines.size() - 1).getClose().doubleValue();
        // deviation > 0 表示价格在 VWAP 上方，< 0 表示在下方，单位 %
        double deviation = vwap > 0 ? (currentClose - vwap) / vwap * 100 : 0;

        return IndicatorResult.builder()
                .type(IndicatorType.VWAP)
                .values(Map.of(
                        "vwap",      round(vwap),
                        "deviation", round(deviation)
                ))
                .build();
    }

    private double getParam(Map<String, Object> params, String key, double defaultValue) {
        if (params == null) return defaultValue;
        Object val = params.get(key);
        return val instanceof Number ? ((Number) val).doubleValue() : defaultValue;
    }

    private double round(double val) {
        return Math.round(val * 100000.0) / 100000.0;
    }
}

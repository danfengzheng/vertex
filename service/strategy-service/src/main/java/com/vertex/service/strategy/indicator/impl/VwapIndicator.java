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
 * 成交量加权平均价格 (VWAP)
 * <p>
 * 日内/短线交易核心指标，判断当前价格相对于成交量加权均价的偏离。
 * <br>
 * 公式: VWAP = Σ(典型价格 × 成交量) / Σ(成交量)
 * <br>
 * 典型价格 = (最高价 + 最低价 + 收盘价) / 3
 * <br>
 * 信号: 价格 < VWAP × 0.998 → BUY (低估), 价格 > VWAP × 1.002 → SELL (高估)
 * </p>
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
        double cumulativeTPV = 0; // 累计 (典型价格 × 成交量)
        double cumulativeVol = 0; // 累计成交量

        for (KLine k : klines) {
            double high = k.getHigh().doubleValue();
            double low = k.getLow().doubleValue();
            double close = k.getClose().doubleValue();
            double volume = k.getVolume().doubleValue();

            double typicalPrice = (high + low + close) / 3.0;
            cumulativeTPV += typicalPrice * volume;
            cumulativeVol += volume;
        }

        // 防止除零
        double vwap = cumulativeVol > 0 ? cumulativeTPV / cumulativeVol : 0;

        double currentClose = klines.get(klines.size() - 1).getClose().doubleValue();
        double deviation = vwap > 0 ? (currentClose - vwap) / vwap * 100 : 0;

        SignalSuggestion suggestion;
        if (currentClose < vwap * 0.998) {
            suggestion = SignalSuggestion.BUY;  // 价格低于 VWAP，可能被低估
        } else if (currentClose > vwap * 1.002) {
            suggestion = SignalSuggestion.SELL; // 价格高于 VWAP，可能被高估
        } else {
            suggestion = SignalSuggestion.NEUTRAL;
        }

        return IndicatorResult.builder()
                .type(IndicatorType.VWAP)
                .values(Map.of(
                        "vwap", round(vwap),
                        "deviation", round(deviation)
                ))
                .suggestion(suggestion)
                .build();
    }

    private double round(double val) {
        return Math.round(val * 100000.0) / 100000.0;
    }
}

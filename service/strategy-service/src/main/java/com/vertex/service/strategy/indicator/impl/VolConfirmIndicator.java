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
 * 成交量确认指标 (Volume Confirmation)
 * <p>
 * 通过对比当前成交量与近期平均成交量来判断是否放量，
 * 结合价格变动方向产生信号。作为辅助指标与趋势指标组合使用，过滤缩量假突破。
 * </p>
 */
@Component
public class VolConfirmIndicator implements TechnicalIndicator {

    @Override
    public IndicatorType type() {
        return IndicatorType.VOL_CONFIRM;
    }

    @Override
    public int requiredDataPoints(Map<String, Object> params) {
        int period = getIntParam(params, "period", 20);
        return period + 1;
    }

    @Override
    public IndicatorResult calculate(List<KLine> klines, Map<String, Object> params) {
        int period = getIntParam(params, "period", 20);

        int size = klines.size();
        KLine current = klines.get(size - 1);

        double currentVolume = current.getVolume().doubleValue();

        // 计算近 period 根K线的平均成交量（不含当前K线）
        int startIdx = Math.max(0, size - 1 - period);
        double sumVolume = 0;
        int count = 0;
        for (int i = startIdx; i < size - 1; i++) {
            sumVolume += klines.get(i).getVolume().doubleValue();
            count++;
        }
        double avgVolume = count > 0 ? sumVolume / count : 0;

        // 成交量比率
        double volRatio = avgVolume > 0 ? currentVolume / avgVolume : 0;

        // 信号判定：放量 + 价格方向（向后兼容）
        double priceChange = size >= 2
                ? klines.get(size - 1).getClose().doubleValue() - klines.get(size - 2).getClose().doubleValue()
                : 0;
        double volMultiplier = getDoubleParam(params, "volMultiplier", 1.5);
        SignalSuggestion suggestion;
        if (volRatio > volMultiplier && priceChange > 0) {
            suggestion = SignalSuggestion.BUY;   // 放量上涨 → 多头确认
        } else if (volRatio > volMultiplier && priceChange < 0) {
            suggestion = SignalSuggestion.SELL;  // 放量下跌 → 空头确认
        } else {
            suggestion = SignalSuggestion.NEUTRAL; // 缩量 → 信号不可靠
        }

        return IndicatorResult.builder()
                .type(IndicatorType.VOL_CONFIRM)
                .values(Map.of(
                        "volRatio", round(volRatio),
                        "avgVolume", round(avgVolume),
                        "currentVolume", round(currentVolume)
                ))
                .suggestion(suggestion)
                .build();
    }

    private int getIntParam(Map<String, Object> params, String key, int defaultValue) {
        if (params == null) return defaultValue;
        Object val = params.get(key);
        return val instanceof Number ? ((Number) val).intValue() : defaultValue;
    }

    private double getDoubleParam(Map<String, Object> params, String key, double defaultValue) {
        if (params == null) return defaultValue;
        Object val = params.get(key);
        return val instanceof Number ? ((Number) val).doubleValue() : defaultValue;
    }

    private double round(double val) {
        return Math.round(val * 100.0) / 100.0;
    }
}

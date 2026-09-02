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
 * 双 EMA 趋势判定指标 —— 单指标内部算 2 条 EMA + 一次到位输出方向。
 * <p>
 * 参数：
 * <ul>
 *   <li>{@code fastPeriod}  快线周期，默认 21</li>
 *   <li>{@code slowPeriod}  慢线周期，默认 55</li>
 *   <li>{@code requirePriceConfirm}  是否要求 close 站上/跌破 fast EMA（默认 true）</li>
 * </ul>
 * </p>
 * <p>
 * 判定规则（模式 C：STATE + 价格确认）：
 * <ul>
 *   <li>BUY  ： EMA_fast &gt; EMA_slow 且（不启用价格确认 或 close &gt; EMA_fast）</li>
 *   <li>SELL ： EMA_fast &lt; EMA_slow 且（不启用价格确认 或 close &lt; EMA_fast）</li>
 *   <li>NEUTRAL：其他所有情况（缠绕、破位）</li>
 * </ul>
 * </p>
 * <p>
 * 输出 values：
 * <ul>
 *   <li>{@code emaFast<period>} —— 快线当前值</li>
 *   <li>{@code emaSlow<period>} —— 慢线当前值</li>
 *   <li>{@code alignment} —— 1=多头排列，-1=空头排列，0=交叉纠缠</li>
 *   <li>{@code priceOverFast} —— 1=close 高于快线，-1=close 低于快线，0=贴合</li>
 * </ul>
 * 可在策略层通过 {@code buyConditions / sellConditions} 引用这些值做更细的判据。
 * </p>
 */
@Component
public class DualEmaTrendIndicator implements TechnicalIndicator {

    private static final double PRICE_TOLERANCE = 0.001;   // 0.1% 贴合容差，避免过于敏感

    @Override
    public IndicatorType type() {
        return IndicatorType.DUAL_EMA_TREND;
    }

    @Override
    public int requiredDataPoints(Map<String, Object> params) {
        int slow = intParam(params, "slowPeriod", 55);
        return slow * 3;
    }

    @Override
    public IndicatorResult calculate(List<KLine> klines, Map<String, Object> params) {
        int fast = intParam(params, "fastPeriod", 21);
        int slow = intParam(params, "slowPeriod", 55);
        boolean requirePriceConfirm = boolParam(params, "requirePriceConfirm", true);

        double emaFast = computeLatestEma(klines, fast);
        double emaSlow = computeLatestEma(klines, slow);
        double close = klines.get(klines.size() - 1).getClose().doubleValue();

        int alignment;
        if (emaFast > emaSlow * (1 + PRICE_TOLERANCE)) {
            alignment = 1;
        } else if (emaFast < emaSlow * (1 - PRICE_TOLERANCE)) {
            alignment = -1;
        } else {
            alignment = 0;
        }

        int priceOverFast;
        if (close > emaFast * (1 + PRICE_TOLERANCE)) {
            priceOverFast = 1;
        } else if (close < emaFast * (1 - PRICE_TOLERANCE)) {
            priceOverFast = -1;
        } else {
            priceOverFast = 0;
        }

        SignalSuggestion suggestion;
        if (alignment == 1 && (!requirePriceConfirm || priceOverFast >= 0)) {
            suggestion = SignalSuggestion.BUY;
        } else if (alignment == -1 && (!requirePriceConfirm || priceOverFast <= 0)) {
            suggestion = SignalSuggestion.SELL;
        } else {
            suggestion = SignalSuggestion.NEUTRAL;
        }

        return IndicatorResult.builder()
                .type(IndicatorType.DUAL_EMA_TREND)
                .values(Map.of(
                        "emaFast" + fast, round(emaFast),
                        "emaSlow" + slow, round(emaSlow),
                        "alignment", (double) alignment,
                        "priceOverFast", (double) priceOverFast))
                .suggestion(suggestion)
                .build();
    }

    /** 常规 EMA 迭代计算：前 period 根取 SMA 做 seed，之后 close × α + prev × (1-α) */
    private static double computeLatestEma(List<KLine> klines, int period) {
        double multiplier = 2.0 / (period + 1);
        double ema = klines.subList(0, period).stream()
                .mapToDouble(k -> k.getClose().doubleValue())
                .average()
                .orElse(0);
        for (int i = period; i < klines.size(); i++) {
            double close = klines.get(i).getClose().doubleValue();
            ema = close * multiplier + ema * (1 - multiplier);
        }
        return ema;
    }

    private static int intParam(Map<String, Object> params, String key, int fallback) {
        Object v = params == null ? null : params.get(key);
        return v instanceof Number ? ((Number) v).intValue() : fallback;
    }

    private static boolean boolParam(Map<String, Object> params, String key, boolean fallback) {
        Object v = params == null ? null : params.get(key);
        if (v instanceof Boolean b) return b;
        if (v instanceof Number n) return n.intValue() != 0;
        return fallback;
    }

    private static double round(double v) {
        return Math.round(v * 100000.0) / 100000.0;
    }
}

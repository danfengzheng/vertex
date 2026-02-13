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
 * 随机 RSI 指标 (Stochastic RSI)
 * <p>
 * 比普通 RSI 更灵敏，适合捕捉短期超买超卖反转，是高频交易常用指标。
 * <br>
 * 步骤:
 * 1. 计算 RSI 序列
 * 2. 对 RSI 序列做 Stochastic 处理: StochRSI = (RSI - RSI_min) / (RSI_max - RSI_min)
 * 3. K = SMA(StochRSI, kSmooth), D = SMA(K, dSmooth)
 * <br>
 * 参数: rsiPeriod(14), stochPeriod(14), kSmooth(3), dSmooth(3)
 * <br>
 * 信号: K上穿D 且 K < 20 → BUY (超卖区金叉), K下穿D 且 K > 80 → SELL (超买区死叉)
 * </p>
 */
@Component
public class StochRsiIndicator implements TechnicalIndicator {

    @Override
    public IndicatorType type() {
        return IndicatorType.STOCH_RSI;
    }

    @Override
    public int requiredDataPoints(Map<String, Object> params) {
        int rsiPeriod = getParam(params, "rsiPeriod", 14);
        int stochPeriod = getParam(params, "stochPeriod", 14);
        int kSmooth = getParam(params, "kSmooth", 3);
        int dSmooth = getParam(params, "dSmooth", 3);
        // RSI 需要 rsiPeriod+1, Stoch 窗口需要 stochPeriod, 平滑需要 kSmooth+dSmooth
        return rsiPeriod + stochPeriod + kSmooth + dSmooth + 5;
    }

    @Override
    public IndicatorResult calculate(List<KLine> klines, Map<String, Object> params) {
        int rsiPeriod = getParam(params, "rsiPeriod", 14);
        int stochPeriod = getParam(params, "stochPeriod", 14);
        int kSmooth = getParam(params, "kSmooth", 3);
        int dSmooth = getParam(params, "dSmooth", 3);

        // Step 1: 计算 RSI 序列
        double[] closes = klines.stream()
                .mapToDouble(k -> k.getClose().doubleValue())
                .toArray();
        double[] rsiSeries = calculateRsiSeries(closes, rsiPeriod);

        // Step 2: 对 RSI 序列做 Stochastic 处理
        double[] stochRsi = new double[rsiSeries.length];
        for (int i = stochPeriod - 1; i < rsiSeries.length; i++) {
            double minRsi = Double.MAX_VALUE;
            double maxRsi = Double.MIN_VALUE;
            for (int j = i - stochPeriod + 1; j <= i; j++) {
                minRsi = Math.min(minRsi, rsiSeries[j]);
                maxRsi = Math.max(maxRsi, rsiSeries[j]);
            }
            double range = maxRsi - minRsi;
            stochRsi[i] = range > 0 ? (rsiSeries[i] - minRsi) / range * 100 : 50;
        }

        // Step 3: K 线 = SMA(StochRSI, kSmooth)
        int stochStart = stochPeriod - 1;
        double[] kLine = sma(stochRsi, kSmooth, stochStart);

        // Step 4: D 线 = SMA(K, dSmooth)
        int kStart = stochStart + kSmooth - 1;
        double[] dLine = sma(kLine, dSmooth, kStart);

        // 取最后两个值判断交叉
        int last = dLine.length - 1;
        int dStart = kStart + dSmooth - 1;

        double currentK = kLine[last];
        double currentD = dLine[last];
        double prevK = last > 0 ? kLine[last - 1] : currentK;
        double prevD = last > 0 ? dLine[last - 1] : currentD;

        SignalSuggestion suggestion;
        if (prevK <= prevD && currentK > currentD && currentK < 20) {
            suggestion = SignalSuggestion.BUY;  // 超卖区金叉
        } else if (prevK >= prevD && currentK < currentD && currentK > 80) {
            suggestion = SignalSuggestion.SELL; // 超买区死叉
        } else {
            suggestion = SignalSuggestion.NEUTRAL;
        }

        return IndicatorResult.builder()
                .type(IndicatorType.STOCH_RSI)
                .values(Map.of(
                        "stochRsiK", round(currentK),
                        "stochRsiD", round(currentD)
                ))
                .suggestion(suggestion)
                .build();
    }

    /**
     * 计算 RSI 序列（Wilder 平滑法）
     */
    private double[] calculateRsiSeries(double[] closes, int period) {
        double[] rsi = new double[closes.length];
        if (closes.length < period + 1) {
            return rsi;
        }

        // 初始平均涨跌幅
        double avgGain = 0, avgLoss = 0;
        for (int i = 1; i <= period; i++) {
            double change = closes[i] - closes[i - 1];
            if (change > 0) avgGain += change;
            else avgLoss += Math.abs(change);
        }
        avgGain /= period;
        avgLoss /= period;

        rsi[period] = avgLoss == 0 ? 100 : 100 - 100.0 / (1 + avgGain / avgLoss);

        // Wilder 平滑
        for (int i = period + 1; i < closes.length; i++) {
            double change = closes[i] - closes[i - 1];
            if (change > 0) {
                avgGain = (avgGain * (period - 1) + change) / period;
                avgLoss = (avgLoss * (period - 1)) / period;
            } else {
                avgGain = (avgGain * (period - 1)) / period;
                avgLoss = (avgLoss * (period - 1) + Math.abs(change)) / period;
            }
            rsi[i] = avgLoss == 0 ? 100 : 100 - 100.0 / (1 + avgGain / avgLoss);
        }
        return rsi;
    }

    /**
     * 简单移动平均（从 startIdx 开始有效）
     */
    private double[] sma(double[] data, int period, int startIdx) {
        double[] result = new double[data.length];
        for (int i = startIdx + period - 1; i < data.length; i++) {
            double sum = 0;
            for (int j = i - period + 1; j <= i; j++) {
                sum += data[j];
            }
            result[i] = sum / period;
        }
        return result;
    }

    private int getParam(Map<String, Object> params, String key, int defaultValue) {
        Object val = params.get(key);
        return val instanceof Number ? ((Number) val).intValue() : defaultValue;
    }

    private double round(double val) {
        return Math.round(val * 100.0) / 100.0;
    }
}

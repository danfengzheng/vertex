package com.vertex.service.strategy.indicator.impl;

import com.vertex.model.entity.quote.KLine;
import com.vertex.model.entity.strategy.IndicatorType;
import com.vertex.service.strategy.indicator.IndicatorResult;
import com.vertex.service.strategy.indicator.TechnicalIndicator;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * MACD 指标
 * <p>
 * 参数: fast (默认12), slow (默认26), signal (默认9)
 * 公式:
 *   MACD Line = EMA(fast) - EMA(slow)
 *   Signal Line = EMA(MACD Line, signal period)
 *   Histogram = MACD Line - Signal Line
 * </p>
 */
@Component
public class MacdIndicator implements TechnicalIndicator {

    @Override
    public IndicatorType type() {
        return IndicatorType.MACD;
    }

    @Override
    public int requiredDataPoints(Map<String, Object> params) {
        int slow = getParam(params, "slow", 26);
        int signal = getParam(params, "signal", 9);
        return slow + signal + 10; // 需要足够的数据稳定 EMA
    }

    @Override
    public IndicatorResult calculate(List<KLine> klines, Map<String, Object> params) {
        int fastPeriod = getParam(params, "fast", 12);
        int slowPeriod = getParam(params, "slow", 26);
        int signalPeriod = getParam(params, "signal", 9);

        double[] closes = klines.stream()
                .mapToDouble(k -> k.getClose().doubleValue())
                .toArray();

        // 计算快速 EMA 和慢速 EMA
        double[] fastEma = calculateEma(closes, fastPeriod);
        double[] slowEma = calculateEma(closes, slowPeriod);

        // MACD Line = Fast EMA - Slow EMA
        int macdStart = slowPeriod - 1; // 从慢速 EMA 第一个有效值开始
        double[] macdLine = new double[closes.length - macdStart];
        for (int i = 0; i < macdLine.length; i++) {
            macdLine[i] = fastEma[i + macdStart] - slowEma[i + macdStart];
        }

        // Signal Line = EMA(MACD Line)
        double[] signalLine = calculateEma(macdLine, signalPeriod);

        // 取最后两个 histogram 值判断交叉
        // histogram[i] = macdLine[i] - signalLine[i]
        // signalLine 与 macdLine 等长，有效信号从 signalPeriod-1 开始，
        // 但判断金叉/死叉只需最后两根，直接用最末两个索引即可。
        int last = signalLine.length - 1;
        double currentHistogram = macdLine[last] - signalLine[last];
        double prevHistogram = last > 0
                ? macdLine[last - 1] - signalLine[last - 1]
                : currentHistogram;

        double macdValue = macdLine[macdLine.length - 1];
        double signalValue = signalLine[signalLine.length - 1];

        return IndicatorResult.builder()
                .type(IndicatorType.MACD)
                .values(Map.of(
                        "macd", round(macdValue),
                        "signal", round(signalValue),
                        "histogram", round(currentHistogram),
                        "histogramPrev", round(prevHistogram)
                ))
                .build();
    }

    /**
     * 计算 EMA 序列
     */
    private double[] calculateEma(double[] data, int period) {
        double[] ema = new double[data.length];
        double multiplier = 2.0 / (period + 1);

        // 初始 SMA
        double sum = 0;
        for (int i = 0; i < period && i < data.length; i++) {
            sum += data[i];
        }
        ema[period - 1] = sum / period;

        // 迭代 EMA
        for (int i = period; i < data.length; i++) {
            ema[i] = data[i] * multiplier + ema[i - 1] * (1 - multiplier);
        }
        return ema;
    }

    private int getParam(Map<String, Object> params, String key, int defaultValue) {
        Object val = params.get(key);
        return val instanceof Number ? ((Number) val).intValue() : defaultValue;
    }

    private double round(double val) {
        return Math.round(val * 100000.0) / 100000.0;
    }
}

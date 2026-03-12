package com.vertex.model.dto.strategy;

import lombok.Data;

import java.io.Serializable;

/**
 * 硬性过滤器的数值条件
 * <p>
 * 用于对指标计算值（IndicatorResult.values）进行阈值校验。
 * 例如：volRatio > 1.5、adx >= 25、trend > 0
 * </p>
 */
@Data
public class FilterCondition implements Serializable {

    /**
     * 指标计算值字段名，对应 IndicatorResult.getValues() 的 key。
     * <ul>
     *   <li>MA: ma20 / ma50 / ma200（随 period 变化）</li>
     *   <li>EMA: ema20 / ema50 / ema200</li>
     *   <li>RSI: rsi14（随 period 变化）</li>
     *   <li>MACD: macd / signal / histogram</li>
     *   <li>BOLL: upper / middle / lower / stdDev</li>
     *   <li>KDJ: k / d / j</li>
     *   <li>ATR: atr / atrPercent</li>
     *   <li>VWAP: vwap / deviation</li>
     *   <li>STOCH_RSI: stochRsiK / stochRsiD</li>
     *   <li>WR: wr14（随 period 变化）</li>
     *   <li>SAR: sar / trend（1.0=上升，-1.0=下降）</li>
     *   <li>ADX: adx / plusDi / minusDi</li>
     *   <li>SUPERTREND: trend（1.0=上升，-1.0=下降）/ superTrend / upperBand / lowerBand</li>
     *   <li>VOL_CONFIRM: volRatio / currentVolume / avgVolume</li>
     *   <li>OBV: obv / obvSignal</li>
     * </ul>
     */
    private String field;

    /**
     * 比较运算符：
     * <ul>
     *   <li>GT  — 严格大于（&gt;）</li>
     *   <li>GTE — 大于等于（&gt;=）</li>
     *   <li>LT  — 严格小于（&lt;）</li>
     *   <li>LTE — 小于等于（&lt;=）</li>
     *   <li>EQ  — 等于（精度 1e-9）</li>
     * </ul>
     */
    private String op;

    /** 阈值，与 field 对应的计算值比较 */
    private Double threshold;
}

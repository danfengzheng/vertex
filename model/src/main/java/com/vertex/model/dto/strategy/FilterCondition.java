package com.vertex.model.dto.strategy;

import lombok.Data;

import java.io.Serializable;

/**
 * 硬性过滤器的数值条件
 * <p>
 * 用于对指标计算值（IndicatorResult.values）进行阈值校验。
 * </p>
 *
 * <h3>双向通用（applyTo=null）</h3>
 * <p>在复合投票<b>之前</b>校验，任一失败则跳过所有指标计算直接返回 NEUTRAL。
 * 适用于市场状态前置门槛，如：{@code adx >= 25}（趋势强度足够才进入分析）。</p>
 *
 * <h3>方向专属（applyTo="BUY" 或 "SELL"）</h3>
 * <p>在复合投票<b>之后</b>按信号方向校验，不影响另一方向的信号生成。
 * 适用于方向性极值条件，如：
 * <ul>
 *   <li>{@code rsi14 > 70, applyTo="SELL"} — 仅在复合信号为 SELL 时要求 RSI 超买</li>
 *   <li>{@code rsi14 < 30, applyTo="BUY"}  — 仅在复合信号为 BUY 时要求 RSI 超卖</li>
 * </ul>
 * </p>
 *
 * <h3>可用字段名（field）</h3>
 * <ul>
 *   <li>MA: ma20 / ma50 / ma200（随 period 变化）</li>
 *   <li>EMA: ema20 / ema50 / ema200</li>
 *   <li>RSI: rsi14（随 period 变化）</li>
 *   <li>MACD: macd / signal / histogram / histogramPrev / histogramDelta（当前-前柱，负值=动能衰减）</li>
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
 *   <li>DIVERGENCE: bearishDivergence（1.0=顶背离出现，0.0=无）/ bullishDivergence（1.0=底背离出现，0.0=无）</li>
 * </ul>
 */
@Data
public class FilterCondition implements Serializable {

    /**
     * 指标计算值字段名，对应 IndicatorResult.getValues() 的 key。
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

    /**
     * 条件适用的信号方向：
     * <ul>
     *   <li>{@code null}  — 双向通用，复合投票<b>前</b>校验，不满足则跳过所有复合计算</li>
     *   <li>{@code "BUY"}  — 仅对买入信号有效，复合投票<b>后</b>校验</li>
     *   <li>{@code "SELL"} — 仅对卖出信号有效，复合投票<b>后</b>校验</li>
     * </ul>
     */
    private String applyTo;
}

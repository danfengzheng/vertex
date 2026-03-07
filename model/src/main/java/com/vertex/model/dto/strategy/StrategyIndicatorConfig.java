package com.vertex.model.dto.strategy;

import com.vertex.model.entity.quote.KLineInterval;
import com.vertex.model.entity.strategy.IndicatorType;
import lombok.Data;

import java.io.Serializable;
import java.util.Map;

/**
 * 策略指标配置（嵌入 JSON 字段）
 */
@Data
public class StrategyIndicatorConfig implements Serializable {

    /** 指标类型 */
    private IndicatorType indicatorType;

    /** 指标参数，如 {"period": 20} 或 {"fast": 12, "slow": 26, "signal": 9} */
    private Map<String, Object> params;

    /** 权重 1-100，用于信号强度加权 */
    private Integer weight;

    /**
     * 跨桶惩罚权重（可选，默认 0）
     * <ul>
     *   <li>BUY  信号：buyWeight += weight，同时 sellWeight -= penaltyWeight（主动削减对立桶）</li>
     *   <li>SELL 信号：sellWeight += weight，同时 buyWeight -= penaltyWeight（主动削减对立桶）</li>
     *   <li>NEUTRAL 信号：neutralWeight += weight，penaltyWeight 额外压制当前主导方向桶</li>
     * </ul>
     * 各桶得分下限为 0，不会产生负值。
     */
    private Integer penaltyWeight;

    /** 指标专属K线周期（可选，为空时使用策略默认周期） */
    private KLineInterval interval;
}

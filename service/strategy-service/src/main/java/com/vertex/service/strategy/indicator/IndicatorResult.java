package com.vertex.service.strategy.indicator;

import com.vertex.model.entity.strategy.IndicatorType;
import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * 指标计算结果
 */
@Data
@Builder
public class IndicatorResult {

    /** 指标类型 */
    private IndicatorType type;

    /** 计算值，如 {"ma20": 95000.5} 或 {"macd": 1.2, "signal": 0.8, "histogram": 0.4} */
    private Map<String, Double> values;

    /**
     * @deprecated 指标不再负责方向判断，改由策略层通过 buyConditions/sellConditions 配置
     */
    @Deprecated
    private SignalSuggestion suggestion;

    public enum SignalSuggestion {
        BUY, SELL, NEUTRAL
    }
}

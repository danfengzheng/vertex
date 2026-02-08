package com.vertex.model.dto.strategy;

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
}

package com.vertex.model.dto.strategy;

import com.vertex.model.entity.quote.KLineInterval;
import com.vertex.model.entity.strategy.IndicatorType;
import lombok.Data;

import java.io.Serializable;
import java.util.List;
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

    /**
     * 是否为硬性过滤器（默认 false）
     * <ul>
     *   <li>true：该指标不参与三桶投票，复合信号产出后独立校验。
     *       <ul>
     *         <li>若 filterConditions 为空 → 方向校验：BUY 信号时此指标必须也返回 BUY，
     *             SELL 信号时必须返回 SELL，否则否决为 NEUTRAL。</li>
     *         <li>若 filterConditions 非空 → 数值校验：计算指标值后逐条检查条件，
     *             全部满足才放行，否则否决为 NEUTRAL（忽略指标方向）。</li>
     *       </ul>
     *   </li>
     *   <li>false（默认）：参与正常的加权投票流程，filterConditions 无效。</li>
     * </ul>
     */
    private Boolean hardFilter;

    /**
     * 数值条件列表（仅 hardFilter=true 时生效）。
     * 非空时走数值校验模式，为空时走方向校验模式。
     * 例如：[{field:"volRatio", op:"GT", threshold:1.5}, {field:"adx", op:"GTE", threshold:25}]
     */
    private List<FilterCondition> filterConditions;
}

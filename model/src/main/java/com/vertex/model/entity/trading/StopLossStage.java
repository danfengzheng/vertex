package com.vertex.model.entity.trading;

/**
 * 移动止损阶段枚举
 * <p>
 * 仅当策略配置了移动止损四参数时生效，用于追踪持仓的止损演进阶段。
 * </p>
 */
public enum StopLossStage {

    /** 初始阶段：持仓刚建立，止损在 entry - initialStopMultiplier × ATR */
    INITIAL,

    /** 保本阶段：价格突破 entry + breakevenActivationMultiplier × ATR，止损移至入场价 */
    BREAKEVEN,

    /** 追踪阶段：利润超过 trailingActivationMultiplier × ATR，止损跟随最优价格 */
    TRAILING
}

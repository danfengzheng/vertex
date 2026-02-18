package com.vertex.model.entity.trading;

/**
 * 仓位计算模式
 */
public enum PositionSizing {

    /** 固定数量 — 使用 tradeQuantity */
    FIXED,

    /** 按资金比例 — 使用 positionRatio * 可用资金 / 当前价格 */
    PERCENT
}

package com.vertex.model.entity.trading;

/**
 * 合约保证金模式
 * <ul>
 *   <li>ISOLATED - 逐仓（每个仓位独立保证金）</li>
 *   <li>CROSS    - 全仓（所有仓位共享保证金）</li>
 * </ul>
 */
public enum MarginType {
    ISOLATED,
    CROSS
}

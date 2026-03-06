package com.vertex.model.entity.trading;

/**
 * 市场类型
 * <ul>
 *   <li>SPOT  - 现货（api.binance.com）</li>
 *   <li>USDM  - U 本位合约（fapi.binance.com，以 USDT 结算）</li>
 *   <li>COINM - 币本位合约（dapi.binance.com，以 BTC/ETH 等结算）</li>
 * </ul>
 */
public enum MarketType {
    SPOT,
    USDM,
    COINM;

    public boolean isFutures() {
        return this == USDM || this == COINM;
    }
}

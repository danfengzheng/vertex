package com.vertex.framework.socket.exchange;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 交易所类型枚举
 */
@Getter
@AllArgsConstructor
public enum ExchangeType {

    BINANCE("binance", "币安"),
    OKX("okx", "OKX"),
    BYBIT("bybit", "Bybit"),
    BITGET("bitget", "Bitget"),
    CUSTOM("custom", "自定义");

    private final String code;
    private final String name;
}

package com.vertex.model.vo.trading;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 交易所币对 VO（前端下拉数据源）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExchangeSymbolVO implements Serializable {

    private Long id;

    /** 平台通用标识，如 ETHUSDT */
    private String symbol;

    /** 标的资产，如 ETH */
    private String baseAsset;

    /** 计价资产，如 USDT */
    private String quoteAsset;

    /** 交易所代码，如 binance */
    private String exchange;

    /** 市场类型：SPOT / USDM / COINM */
    private String marketType;

    /** 交易所实际标识 */
    private String exchangeSymbol;

    /** 状态：TRADING / BREAK */
    private String status;

    /** 数量步长 */
    private BigDecimal lotSize;

    /** 价格步长 */
    private BigDecimal tickSize;
}

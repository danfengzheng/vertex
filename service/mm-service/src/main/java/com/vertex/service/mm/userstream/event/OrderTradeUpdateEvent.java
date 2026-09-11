package com.vertex.service.mm.userstream.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 币安私有流 ORDER_TRADE_UPDATE 事件（订单状态变化）。
 * <p>
 * 币安推送字段简化为下面这些 —— GridEngine 补单最需要的信息。
 * 完整字段见 https://binance-docs.github.io/apidocs/futures/en/#event-order-update
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderTradeUpdateEvent {

    /** event time (E) */
    private long eventTimeMs;
    /** transaction time (T) */
    private long transactionTimeMs;

    private String symbol;
    /** 币安 orderId (i) */
    private long exchangeOrderId;
    /** 我方 clientOrderId (c) */
    private String clientOrderId;

    /** BUY / SELL */
    private String side;
    /** LONG / SHORT / BOTH */
    private String positionSide;

    private String type;
    private String timeInForce;

    /** 原始委托量 (q) */
    private BigDecimal originalQty;
    /** 原始委托价 (p) */
    private BigDecimal originalPrice;
    /** 本次成交量 (l) */
    private BigDecimal lastFilledQty;
    /** 本次成交价 (L) */
    private BigDecimal lastFilledPrice;
    /** 累积成交量 (z) */
    private BigDecimal cumulativeFilledQty;
    /** 累积成交金额 (Z) */
    private BigDecimal cumulativeQuote;

    /** NEW / PARTIALLY_FILLED / FILLED / CANCELED / REJECTED / EXPIRED */
    private String orderStatus;
    /** NEW / TRADE / CANCELED / CALCULATED / EXPIRED / TRADE / AMENDMENT */
    private String execType;

    /** 本次手续费 (n) 应为 0（GTX maker） */
    private BigDecimal fee;
    /** 手续费币种 (N) */
    private String feeAsset;

    /** 是否 maker (m) */
    private boolean maker;
}

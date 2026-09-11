package com.vertex.service.mm.binance;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 挂单请求 —— 严格 GTX（Good Till Crossing = post-only = maker-only）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MmOrderRequest {

    private String symbol;
    /** BUY / SELL */
    private String side;
    /** LONG / SHORT / BOTH（hedge 模式必填 LONG/SHORT）*/
    private String positionSide;
    private BigDecimal price;
    private BigDecimal quantity;
    /** 我方生成的 client order id，用于幂等 */
    private String clientOrderId;
}

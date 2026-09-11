package com.vertex.service.mm.paper;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/** Paper 挂单簿里的一笔单 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class PaperOrder {
    /** 币安风格的伪 orderId（本地自增）*/
    private Long paperOrderId;
    private String clientOrderId;

    private String symbol;
    /** BUY / SELL */
    private String side;
    /** LONG / SHORT / BOTH */
    private String positionSide;

    private BigDecimal price;
    private BigDecimal quantity;
    private BigDecimal filledQty;

    private long placedTimeMs;
    private long lastFillTimeMs;
    /** 记录本单在挂上时该价位已经有多少排队量（决定被吃时的优先级）*/
    private BigDecimal queueAheadQty;

    /** NEW / PARTIALLY_FILLED / FILLED / CANCELED / REJECTED */
    private String status;
}

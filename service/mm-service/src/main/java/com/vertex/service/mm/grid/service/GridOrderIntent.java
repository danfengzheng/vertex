package com.vertex.service.mm.grid.service;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 挂单/撤单意图 —— SlidingWindowManager 输出，GridEngine 执行。
 * <p>
 * 把"状态变化"和"实际下单"解耦：SlidingWindowManager 只维护状态 + 输出意图；
 * GridEngine 拿到意图后 batch 调用 MmOrderClient 落实。
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GridOrderIntent {

    public enum ActionType {
        PLACE,   // 新挂单
        CANCEL   // 撤销
    }

    private long strategyId;
    private String symbol;

    private ActionType actionType;

    /** 挂单字段 (PLACE) */
    private String side;             // BUY / SELL
    private String positionSide;     // LONG / SHORT
    private String role;             // OPEN / CLOSE / LADDER_CLOSE
    private BigDecimal price;
    private BigDecimal qty;
    private Integer gridIndex;
    private String clientOrderId;

    /** 撤单字段 (CANCEL) */
    private Long exchangeOrderId;

    public static GridOrderIntent cancel(long strategyId, String symbol, Long orderId,
                                          BigDecimal price, String positionSide) {
        return GridOrderIntent.builder()
                .strategyId(strategyId)
                .symbol(symbol)
                .actionType(ActionType.CANCEL)
                .exchangeOrderId(orderId)
                .price(price)
                .positionSide(positionSide)
                .build();
    }
}

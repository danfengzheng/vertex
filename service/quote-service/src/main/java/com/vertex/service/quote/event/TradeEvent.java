package com.vertex.service.quote.event;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 逐笔成交事件（用于本地聚合 K 线）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TradeEvent {

    private String exchange;
    private String symbol;
    /** 成交价 */
    private BigDecimal price;
    /** 成交量（base） */
    private BigDecimal quantity;
    /** 成交时间（毫秒） */
    private long timeMs;
}

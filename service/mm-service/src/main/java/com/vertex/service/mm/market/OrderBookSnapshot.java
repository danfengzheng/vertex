package com.vertex.service.mm.market;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * 10 档买卖盘快照。immutable。
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class OrderBookSnapshot {
    private String symbol;
    /** 买盘（价格降序：bids[0] = best bid）*/
    private List<OrderBookLevel> bids;
    /** 卖盘（价格升序：asks[0] = best ask）*/
    private List<OrderBookLevel> asks;
    private long updateTimeMs;

    public BigDecimal bestBid() { return bids != null && !bids.isEmpty() ? bids.get(0).getPrice() : null; }
    public BigDecimal bestAsk() { return asks != null && !asks.isEmpty() ? asks.get(0).getPrice() : null; }

    /**
     * 计算某价格档位的排队量（同侧同价的所有单加起来）。
     * 用于 Paper 模式排队损失计算。
     */
    public BigDecimal queueQtyAtPrice(BigDecimal price, String side) {
        List<OrderBookLevel> levels = "BUY".equalsIgnoreCase(side) ? bids : asks;
        if (levels == null) return BigDecimal.ZERO;
        for (OrderBookLevel l : levels) {
            if (l.getPrice().compareTo(price) == 0) return l.getQty();
        }
        return BigDecimal.ZERO;
    }

    /**
     * 累计到某价格档的总排队量（bids 从 best_bid 到 price；asks 从 best_ask 到 price）。
     * 用于智能挂单价决策：如果某档累计排队 &gt; 阈值，说明该档"薄"或"过厚"。
     */
    public BigDecimal cumulativeQtyToPrice(BigDecimal price, String side) {
        List<OrderBookLevel> levels = "BUY".equalsIgnoreCase(side) ? bids : asks;
        if (levels == null) return BigDecimal.ZERO;
        BigDecimal sum = BigDecimal.ZERO;
        for (OrderBookLevel l : levels) {
            boolean include = "BUY".equalsIgnoreCase(side)
                    ? l.getPrice().compareTo(price) >= 0    // bids 价格 ≥ price
                    : l.getPrice().compareTo(price) <= 0;   // asks 价格 ≤ price
            if (include) sum = sum.add(l.getQty());
        }
        return sum;
    }
}

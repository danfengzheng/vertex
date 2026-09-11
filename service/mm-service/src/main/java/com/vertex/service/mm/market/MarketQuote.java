package com.vertex.service.mm.market;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

/**
 * bid/ask 快照。immutable。
 */
@Data
@Builder
@AllArgsConstructor
public class MarketQuote {
    private final String symbol;
    private final BigDecimal bestBid;
    private final BigDecimal bestBidQty;
    private final BigDecimal bestAsk;
    private final BigDecimal bestAskQty;
    private final long updateTimeMs;

    /** mid = (bid + ask) / 2 —— 用于 mark price 参考 */
    public BigDecimal mid() {
        if (bestBid == null || bestAsk == null) return null;
        return bestBid.add(bestAsk).divide(BigDecimal.valueOf(2), 8, java.math.RoundingMode.HALF_UP);
    }

    /** spread = ask - bid */
    public BigDecimal spread() {
        if (bestBid == null || bestAsk == null) return null;
        return bestAsk.subtract(bestBid);
    }

    /** spread% = (ask - bid) / mid */
    public BigDecimal spreadPct() {
        BigDecimal mid = mid();
        BigDecimal spread = spread();
        if (mid == null || spread == null || mid.compareTo(BigDecimal.ZERO) == 0) return null;
        return spread.divide(mid, 8, java.math.RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
    }
}

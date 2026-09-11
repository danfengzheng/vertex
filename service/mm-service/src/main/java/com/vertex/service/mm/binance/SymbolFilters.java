package com.vertex.service.mm.binance;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 币安 exchangeInfo 里单个 symbol 的关键过滤规则。
 * <p>
 * 所有下单前的价/量必须先经这里 round，否则会被 -1013 (Filter failure) 拒单。
 * </p>
 */
@Getter
@Builder
public class SymbolFilters {

    private final String symbol;
    /** PRICE_FILTER.tickSize — 价格必须是此值的整数倍 */
    private final BigDecimal tickSize;
    /** LOT_SIZE.stepSize — 数量必须是此值的整数倍 */
    private final BigDecimal stepSize;
    /** LOT_SIZE.minQty — 单笔最小数量 */
    private final BigDecimal minQty;
    /** MIN_NOTIONAL.notional — 单笔最小名义金额 (USDC/USDT) */
    private final BigDecimal minNotional;
    /** 价格精度位数（tickSize 换算） */
    private final int pricePrecision;
    /** 数量精度位数（stepSize 换算） */
    private final int qtyPrecision;

    /**
     * 按 tickSize round 价格。
     * @param side BUY / SELL；BUY 向下取（更保守，避免多付）；SELL 向上取（更保守，避免少收）
     */
    public BigDecimal roundPrice(BigDecimal rawPrice, String side) {
        if (rawPrice == null || tickSize == null || tickSize.compareTo(BigDecimal.ZERO) <= 0) return rawPrice;
        RoundingMode mode = "SELL".equalsIgnoreCase(side) ? RoundingMode.CEILING : RoundingMode.FLOOR;
        BigDecimal ticks = rawPrice.divide(tickSize, 0, mode);
        return ticks.multiply(tickSize).setScale(pricePrecision, RoundingMode.HALF_UP);
    }

    /** 按 stepSize round 数量（永远向下取整，宁少不多）*/
    public BigDecimal roundQty(BigDecimal rawQty) {
        if (rawQty == null || stepSize == null || stepSize.compareTo(BigDecimal.ZERO) <= 0) return rawQty;
        BigDecimal steps = rawQty.divide(stepSize, 0, RoundingMode.FLOOR);
        return steps.multiply(stepSize).setScale(qtyPrecision, RoundingMode.HALF_UP);
    }

    /**
     * 校验 (price, qty) 是否满足 minQty + minNotional。
     * @return null 表示合法；否则返回错误原因
     */
    public String validate(BigDecimal price, BigDecimal qty) {
        if (qty == null || qty.compareTo(BigDecimal.ZERO) <= 0) return "qty must be positive";
        if (minQty != null && qty.compareTo(minQty) < 0) {
            return "qty " + qty + " < minQty " + minQty;
        }
        if (price != null && minNotional != null) {
            BigDecimal notional = price.multiply(qty);
            if (notional.compareTo(minNotional) < 0) {
                return "notional " + notional + " < minNotional " + minNotional;
            }
        }
        return null;
    }
}

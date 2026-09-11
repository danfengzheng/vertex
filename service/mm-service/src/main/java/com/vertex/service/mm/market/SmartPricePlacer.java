package com.vertex.service.mm.market;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 智能挂单价选择器 —— 基于 10 档深度调整原始挂单价。
 * <p>
 * <b>目标</b>：避开薄簿位（排队量 &lt; 一定阈值的档），跳到旁边有真实排队的价位。
 * 薄簿位挂单往往被瞬时穿透后没成交（价格反弹），或成交后价格立即逆行（adverse selection）。
 * </p>
 * <p>
 * <b>决策</b>：
 * <ul>
 *   <li>目标价 P 处的排队量 &gt; minLiquidityQty → 直接用 P</li>
 *   <li>否则往有利方向找最近的"厚"档（BUY 找更低价；SELL 找更高价）</li>
 *   <li>找不到（10 档内都薄）→ 保持 P（挂上去也许有人跟单）</li>
 * </ul>
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SmartPricePlacer {

    private final OrderBookCache orderBookCache;

    /** 挂单最少排队量（BTC 上 0.5，ETH 上 5，可按 symbol 调）*/
    private static final BigDecimal DEFAULT_MIN_LIQUIDITY = new BigDecimal("2.0");

    /**
     * 调整挂单价。
     *
     * @param symbol      交易对
     * @param originalPrice 原始网格价
     * @param side        BUY / SELL
     * @return 调整后的挂单价（可能等于原价）
     */
    public BigDecimal adjust(String symbol, BigDecimal originalPrice, String side) {
        return adjust(symbol, originalPrice, side, DEFAULT_MIN_LIQUIDITY);
    }

    public BigDecimal adjust(String symbol, BigDecimal originalPrice, String side, BigDecimal minLiquidity) {
        OrderBookSnapshot snap = orderBookCache.get(symbol);
        if (snap == null) return originalPrice;   // 无深度数据 → 不调整

        boolean isBuy = "BUY".equalsIgnoreCase(side);
        var levels = isBuy ? snap.getBids() : snap.getAsks();
        if (levels == null || levels.isEmpty()) return originalPrice;

        // 找目标价对应或最近的档
        for (OrderBookLevel l : levels) {
            // BUY：找 price ≤ originalPrice 且第一档 qty ≥ minLiquidity
            // SELL：找 price ≥ originalPrice 且第一档 qty ≥ minLiquidity
            boolean matches = isBuy
                    ? l.getPrice().compareTo(originalPrice) <= 0
                    : l.getPrice().compareTo(originalPrice) >= 0;
            if (!matches) continue;

            if (l.getQty().compareTo(minLiquidity) >= 0) {
                if (l.getPrice().compareTo(originalPrice) != 0) {
                    log.debug("[SmartPricer] {} {} price {} → {} (queue qty={})",
                            symbol, side, originalPrice, l.getPrice(), l.getQty());
                }
                return l.getPrice().setScale(originalPrice.scale(), RoundingMode.HALF_UP);
            }
        }

        // 全部档都薄 → 保持原价
        return originalPrice;
    }

    /**
     * 检查某价位是否流动性充足（>= min）。
     * 供 SlidingWindowManager 在生成 intent 时可选调用。
     */
    public boolean hasEnoughLiquidity(String symbol, BigDecimal price, String side, BigDecimal minQty) {
        OrderBookSnapshot snap = orderBookCache.get(symbol);
        if (snap == null) return true;   // 无数据视为通过（不阻塞挂单）
        BigDecimal q = snap.queueQtyAtPrice(price, side);
        return q.compareTo(minQty) >= 0;
    }
}

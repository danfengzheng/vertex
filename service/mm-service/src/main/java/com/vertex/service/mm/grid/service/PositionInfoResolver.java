package com.vertex.service.mm.grid.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.vertex.service.mm.grid.entity.GridOrder;
import com.vertex.service.mm.grid.entity.GridStrategy;
import com.vertex.service.mm.grid.mapper.GridOrderMapper;
import com.vertex.service.mm.market.MarketQuote;
import com.vertex.service.mm.market.MarketQuoteCache;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * 根据 grid_order 表 + 当前 mark price 计算某侧持仓信息。
 * <p>
 * 用于 Layer 2B/3/4 判断"该侧累积仓位 + 平均成本 + 浮亏"。
 * </p>
 */
@Component
@RequiredArgsConstructor
public class PositionInfoResolver {

    private final GridOrderMapper orderMapper;
    private final MarketQuoteCache quoteCache;

    /**
     * 计算某策略某侧的持仓摘要。
     */
    public PositionSummary resolve(GridStrategy strategy, String positionSide) {
        // 查该侧所有 OPEN 成交且未被 CLOSE 平掉的 order
        // 简化：所有 role=OPEN 且 status=FILLED 且未与 CLOSE 配对的
        List<GridOrder> openFills = orderMapper.selectList(new LambdaQueryWrapper<GridOrder>()
                .eq(GridOrder::getGridStrategyId, strategy.getId())
                .eq(GridOrder::getPositionSide, positionSide)
                .eq(GridOrder::getRole, "OPEN")
                .in(GridOrder::getStatus, "FILLED", "PARTIAL")
                .isNull(GridOrder::getPairOrderId));

        BigDecimal totalQty = BigDecimal.ZERO;
        BigDecimal totalCost = BigDecimal.ZERO;
        for (GridOrder o : openFills) {
            BigDecimal qty = o.getFilledQty() != null ? o.getFilledQty() : o.getQty();
            BigDecimal price = o.getAvgFillPrice() != null ? o.getAvgFillPrice() : o.getGridPrice();
            if (qty == null || price == null) continue;
            totalQty = totalQty.add(qty);
            totalCost = totalCost.add(qty.multiply(price));
        }

        if (totalQty.compareTo(BigDecimal.ZERO) <= 0) {
            return PositionSummary.empty(strategy.getId(), positionSide);
        }

        BigDecimal avgCost = totalCost.divide(totalQty, 8, RoundingMode.HALF_UP);
        MarketQuote quote = quoteCache.get(strategy.getSymbol());
        BigDecimal markPrice = quote == null ? null : quote.mid();
        BigDecimal unrealizedPnl = null;
        if (markPrice != null) {
            boolean isLong = "LONG".equalsIgnoreCase(positionSide);
            unrealizedPnl = isLong
                    ? markPrice.subtract(avgCost).multiply(totalQty)
                    : avgCost.subtract(markPrice).multiply(totalQty);
        }

        return PositionSummary.builder()
                .strategyId(strategy.getId())
                .positionSide(positionSide)
                .totalQty(totalQty)
                .avgCost(avgCost)
                .markPrice(markPrice)
                .unrealizedPnl(unrealizedPnl)
                .openOrdersCount(openFills.size())
                .build();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PositionSummary {
        private long strategyId;
        private String positionSide;
        private BigDecimal totalQty;
        private BigDecimal avgCost;
        private BigDecimal markPrice;
        private BigDecimal unrealizedPnl;
        private int openOrdersCount;

        public boolean isEmpty() {
            return totalQty == null || totalQty.compareTo(BigDecimal.ZERO) <= 0;
        }

        public static PositionSummary empty(long strategyId, String side) {
            return PositionSummary.builder()
                    .strategyId(strategyId).positionSide(side)
                    .totalQty(BigDecimal.ZERO).openOrdersCount(0).build();
        }

        /** 浮亏占本金百分比（需要 principal 参数）*/
        public BigDecimal pnlPctVsPrincipal(BigDecimal principal) {
            if (unrealizedPnl == null || principal == null || principal.compareTo(BigDecimal.ZERO) <= 0) return null;
            return unrealizedPnl.divide(principal, 6, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100));
        }
    }
}

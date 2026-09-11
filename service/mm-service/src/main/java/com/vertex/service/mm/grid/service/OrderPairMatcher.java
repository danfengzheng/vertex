package com.vertex.service.mm.grid.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.vertex.service.mm.grid.entity.GridOrder;
import com.vertex.service.mm.grid.entity.GridStrategy;
import com.vertex.service.mm.grid.mapper.GridOrderMapper;
import com.vertex.service.mm.grid.mapper.GridStrategyMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 订单配对器 —— 网格 P&L 归因核心。
 * <p>
 * 每次 CLOSE 单成交时，找配对的 OPEN 单，计算 pair_pnl = 净利润，归为 arbitrage_pnl。
 * 未配对的持仓（单向累积）归为 directional_pnl。
 * </p>
 * <p>
 * <b>为什么重要</b>：网格真正的价值在 arbitrage_pnl（网格套利部分），
 * directional_pnl 是趋势方向敞口的"副产品"。<br>
 * 长期看 arbitrage 应稳定为正、directional 应接近 0。<br>
 * 如果 directional 持续为负 → 说明趋势保护失效或参数不对。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderPairMatcher {

    private final GridOrderMapper orderMapper;
    private final GridStrategyMapper strategyMapper;
    private final GridEventLogger eventLogger;

    /**
     * CLOSE 单成交时调用。找到对应的 OPEN 单 → 计算 arbitrage_pnl → 累加到策略。
     *
     * @param strategy    策略
     * @param closeOrder  刚成交的 CLOSE 单（filled_qty / avg_fill_price 已设）
     * @return pair P&L（正数 = 盈利；null 表示配对失败）
     */
    public BigDecimal onCloseFilled(GridStrategy strategy, GridOrder closeOrder) {
        // 找同策略、同 positionSide、role=OPEN、status=FILLED、未配对（pair_order_id IS NULL）
        // 的最早一笔（FIFO 配对）
        List<GridOrder> candidates = orderMapper.selectList(new LambdaQueryWrapper<GridOrder>()
                .eq(GridOrder::getGridStrategyId, strategy.getId())
                .eq(GridOrder::getPositionSide, closeOrder.getPositionSide())
                .eq(GridOrder::getRole, "OPEN")
                .in(GridOrder::getStatus, "FILLED", "PARTIAL")
                .isNull(GridOrder::getPairOrderId)
                .orderByAsc(GridOrder::getFilledAt)
                .last("LIMIT 5"));

        if (candidates.isEmpty()) {
            log.warn("[PairMatcher] no OPEN candidate for CLOSE {} strategy={}",
                    closeOrder.getId(), strategy.getId());
            return null;
        }

        BigDecimal remainingCloseQty = closeOrder.getFilledQty() != null
                ? closeOrder.getFilledQty() : closeOrder.getQty();
        BigDecimal closePrice = closeOrder.getAvgFillPrice() != null
                ? closeOrder.getAvgFillPrice() : closeOrder.getGridPrice();
        boolean isLong = "LONG".equalsIgnoreCase(closeOrder.getPositionSide());
        BigDecimal totalPnl = BigDecimal.ZERO;

        for (GridOrder open : candidates) {
            if (remainingCloseQty.compareTo(BigDecimal.ZERO) <= 0) break;
            BigDecimal openQty = open.getFilledQty() != null ? open.getFilledQty() : open.getQty();
            BigDecimal openPrice = open.getAvgFillPrice() != null ? open.getAvgFillPrice() : open.getGridPrice();
            BigDecimal matchQty = openQty.min(remainingCloseQty);

            // LONG round-trip: pnl = (closePrice - openPrice) × qty
            // SHORT round-trip: pnl = (openPrice - closePrice) × qty
            BigDecimal pnl = isLong
                    ? closePrice.subtract(openPrice).multiply(matchQty)
                    : openPrice.subtract(closePrice).multiply(matchQty);
            totalPnl = totalPnl.add(pnl);

            // 标记 open 单已配对
            try {
                orderMapper.update(null, new LambdaUpdateWrapper<GridOrder>()
                        .eq(GridOrder::getId, open.getId())
                        .set(GridOrder::getPairOrderId, closeOrder.getId()));
            } catch (Exception ignored) {}

            remainingCloseQty = remainingCloseQty.subtract(matchQty);
        }

        // 累加到策略的 total_arbitrage_pnl
        try {
            GridStrategy s = strategyMapper.selectById(strategy.getId());
            if (s != null) {
                BigDecimal current = s.getTotalArbitragePnl() != null
                        ? s.getTotalArbitragePnl() : BigDecimal.ZERO;
                strategyMapper.update(null, new LambdaUpdateWrapper<GridStrategy>()
                        .eq(GridStrategy::getId, strategy.getId())
                        .set(GridStrategy::getTotalArbitragePnl,
                                current.add(totalPnl).setScale(8, RoundingMode.HALF_UP)));
            }
        } catch (Exception ex) {
            log.warn("[PairMatcher] persist arbitrage_pnl failed: {}", ex.getMessage());
        }

        // 事件日志
        Map<String, Object> payload = new HashMap<>();
        payload.put("closeOrderId", closeOrder.getId());
        payload.put("closePrice", closePrice);
        payload.put("closeQty", closeOrder.getFilledQty());
        payload.put("pairedOpenCount", candidates.size() - (remainingCloseQty.compareTo(BigDecimal.ZERO) > 0 ? 1 : 0));
        payload.put("pnl", totalPnl);
        eventLogger.info(strategy.getId(), "ARBITRAGE_ROUND_TRIP", payload);

        log.info("[PairMatcher] strategy={} closed pair pnl={} (unmatched close qty={})",
                strategy.getId(), totalPnl, remainingCloseQty);
        return totalPnl;
    }
}

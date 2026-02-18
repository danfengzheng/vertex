package com.vertex.service.order.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.vertex.model.entity.trading.*;
import com.vertex.service.order.mapper.PositionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 持仓管理服务
 * <p>
 * 负责开仓、加仓、平仓、止盈止损检查。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PositionManagementService {

    private final PositionMapper positionMapper;

    /**
     * 根据成交订单更新持仓
     */
    public void updatePosition(Order order) {
        if (order.getSide() == OrderSide.BUY) {
            handleBuy(order);
        } else if (order.getSide() == OrderSide.SELL) {
            handleSell(order);
        }
    }

    /**
     * 处理买入 — 开仓或加仓
     */
    private void handleBuy(Order order) {
        Position existing = findOpenPosition(order.getStrategyId(), order.getAccountId(),
                order.getExchange(), order.getSymbol());

        if (existing == null) {
            // 开仓
            Position position = new Position();
            position.setStrategyId(order.getStrategyId());
            position.setAccountId(order.getAccountId());
            position.setExchange(order.getExchange());
            position.setSymbol(order.getSymbol());
            position.setSide(PositionSide.LONG);
            position.setQuantity(order.getFilledQuantity());
            position.setEntryPrice(order.getFilledPrice());
            position.setCurrentPrice(order.getFilledPrice());
            position.setUnrealizedPnl(BigDecimal.ZERO);
            position.setRealizedPnl(BigDecimal.ZERO);
            position.setStatus(PositionStatus.OPEN);
            position.setTradeMode(order.getTradeMode());

            positionMapper.insert(position);
            log.info("Position opened: {} {} qty={} entry={}",
                    order.getExchange(), order.getSymbol(), position.getQuantity(), position.getEntryPrice());
        } else {
            // 加仓 — 更新均价
            BigDecimal totalQty = existing.getQuantity().add(order.getFilledQuantity());
            BigDecimal totalCost = existing.getEntryPrice().multiply(existing.getQuantity())
                    .add(order.getFilledPrice().multiply(order.getFilledQuantity()));
            BigDecimal newAvgPrice = totalCost.divide(totalQty, 10, RoundingMode.HALF_UP);

            existing.setQuantity(totalQty);
            existing.setEntryPrice(newAvgPrice);
            existing.setCurrentPrice(order.getFilledPrice());
            updateUnrealizedPnl(existing);

            positionMapper.updateById(existing);
            log.info("Position increased: {} {} qty={} avgPrice={}",
                    order.getExchange(), order.getSymbol(), totalQty, newAvgPrice);
        }
    }

    /**
     * 处理卖出 — 减仓或平仓
     */
    private void handleSell(Order order) {
        Position existing = findOpenPosition(order.getStrategyId(), order.getAccountId(),
                order.getExchange(), order.getSymbol());

        if (existing == null) {
            log.warn("No open position found for sell order: {} {}", order.getExchange(), order.getSymbol());
            return;
        }

        BigDecimal sellQty = order.getFilledQuantity();
        BigDecimal pnl = order.getFilledPrice().subtract(existing.getEntryPrice())
                .multiply(sellQty);

        if (sellQty.compareTo(existing.getQuantity()) >= 0) {
            // 全部平仓
            existing.setQuantity(BigDecimal.ZERO);
            existing.setRealizedPnl(existing.getRealizedPnl().add(pnl));
            existing.setUnrealizedPnl(BigDecimal.ZERO);
            existing.setCurrentPrice(order.getFilledPrice());
            existing.setClosePrice(order.getFilledPrice());
            existing.setClosedAt(LocalDateTime.now());
            existing.setStatus(PositionStatus.CLOSED);

            positionMapper.updateById(existing);
            log.info("Position closed: {} {} closePrice={} pnl={}",
                    order.getExchange(), order.getSymbol(), order.getFilledPrice(), pnl);
        } else {
            // 部分平仓
            existing.setQuantity(existing.getQuantity().subtract(sellQty));
            existing.setRealizedPnl(existing.getRealizedPnl().add(pnl));
            existing.setCurrentPrice(order.getFilledPrice());
            updateUnrealizedPnl(existing);

            positionMapper.updateById(existing);
            log.info("Position reduced: {} {} remainQty={} pnl={}",
                    order.getExchange(), order.getSymbol(), existing.getQuantity(), pnl);
        }
    }

    /**
     * 手动平仓
     */
    public void closePosition(Position position, BigDecimal closePrice) {
        BigDecimal pnl = closePrice.subtract(position.getEntryPrice())
                .multiply(position.getQuantity());

        position.setQuantity(BigDecimal.ZERO);
        position.setCurrentPrice(closePrice);
        position.setClosePrice(closePrice);
        position.setClosedAt(LocalDateTime.now());
        position.setRealizedPnl(position.getRealizedPnl().add(pnl));
        position.setUnrealizedPnl(BigDecimal.ZERO);
        position.setStatus(PositionStatus.CLOSED);

        positionMapper.updateById(position);
        log.info("Position manually closed: {} {} closePrice={} pnl={}",
                position.getExchange(), position.getSymbol(), closePrice, pnl);
    }

    /**
     * 检查止盈止损
     *
     * @return 是否触发了止盈止损
     */
    public boolean checkStopLossTakeProfit(Position position, BigDecimal currentPrice,
                                            BigDecimal stopLossPct, BigDecimal takeProfitPct) {
        if (position.getStatus() != PositionStatus.OPEN) {
            return false;
        }

        BigDecimal entryPrice = position.getEntryPrice();

        // 止损检查
        if (stopLossPct != null && stopLossPct.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal stopLossPrice = entryPrice.multiply(
                    BigDecimal.ONE.subtract(stopLossPct.divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP)));
            if (currentPrice.compareTo(stopLossPrice) <= 0) {
                log.info("Stop loss triggered: {} {} currentPrice={} stopLossPrice={}",
                        position.getExchange(), position.getSymbol(), currentPrice, stopLossPrice);
                closePosition(position, currentPrice);
                return true;
            }
        }

        // 止盈检查
        if (takeProfitPct != null && takeProfitPct.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal takeProfitPrice = entryPrice.multiply(
                    BigDecimal.ONE.add(takeProfitPct.divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP)));
            if (currentPrice.compareTo(takeProfitPrice) >= 0) {
                log.info("Take profit triggered: {} {} currentPrice={} takeProfitPrice={}",
                        position.getExchange(), position.getSymbol(), currentPrice, takeProfitPrice);
                closePosition(position, currentPrice);
                return true;
            }
        }

        return false;
    }

    /**
     * 查找策略下的活跃持仓
     */
    public Position findOpenPosition(Long strategyId, Long accountId, String exchange, String symbol) {
        LambdaQueryWrapper<Position> wrapper = new LambdaQueryWrapper<Position>()
                .eq(Position::getStrategyId, strategyId)
                .eq(Position::getAccountId, accountId)
                .eq(Position::getExchange, exchange)
                .eq(Position::getSymbol, symbol)
                .eq(Position::getStatus, PositionStatus.OPEN)
                .eq(Position::getDeleted, 0)
                .last("LIMIT 1");
        return positionMapper.selectOne(wrapper);
    }

    /**
     * 更新持仓的当前价格和未实现盈亏（持久化到数据库）
     */
    public void updateCurrentPrice(Position position) {
        positionMapper.updateById(position);
    }

    /**
     * 更新持仓的止盈止损价格（持久化到数据库）
     */
    public void updateStopLossTakeProfit(Position position) {
        positionMapper.updateById(position);
        log.info("SL/TP updated: {} {} stopLoss={} takeProfit={}",
                position.getExchange(), position.getSymbol(),
                position.getStopLoss(), position.getTakeProfit());
    }

    /**
     * 查询所有活跃持仓（用于定时止盈止损检查）
     */
    public List<Position> findAllOpenPositions() {
        LambdaQueryWrapper<Position> wrapper = new LambdaQueryWrapper<Position>()
                .eq(Position::getStatus, PositionStatus.OPEN)
                .eq(Position::getDeleted, 0);
        return positionMapper.selectList(wrapper);
    }

    private void updateUnrealizedPnl(Position position) {
        if (position.getCurrentPrice() != null && position.getEntryPrice() != null
                && position.getQuantity().compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal unrealized = position.getCurrentPrice().subtract(position.getEntryPrice())
                    .multiply(position.getQuantity());
            position.setUnrealizedPnl(unrealized);
        }
    }
}

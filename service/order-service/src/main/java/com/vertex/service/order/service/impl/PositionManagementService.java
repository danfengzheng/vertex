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
import java.time.ZoneOffset;
import java.util.List;

/**
 * 持仓管理服务
 * <p>
 * 负责开仓、加仓、平仓、止盈止损检查。
 * 支持现货（仅 LONG）和合约（LONG / SHORT）两种模式。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PositionManagementService {

    private final PositionMapper positionMapper;

    // ─── 核心入口 ─────────────────────────────────────────────

    /**
     * 根据成交订单更新持仓
     * <p>
     * 现货：BUY → 开/加 LONG；SELL → 减/平 LONG<br>
     * 合约（isFutures）：
     * <ul>
     *   <li>reduceOnly=false, BUY  → 开/加 LONG</li>
     *   <li>reduceOnly=false, SELL → 开/加 SHORT</li>
     *   <li>reduceOnly=true,  SELL → 减/平 LONG</li>
     *   <li>reduceOnly=true,  BUY  → 减/平 SHORT</li>
     * </ul>
     * </p>
     */
    public void updatePosition(Order order) {
        MarketType mt = order.getMarketType();
        boolean isFutures = mt != null && mt.isFutures();

        if (isFutures) {
            handleFutures(order);
        } else {
            // 现货逻辑（不变）
            if (order.getSide() == OrderSide.BUY) {
                handleBuy(order);
            } else if (order.getSide() == OrderSide.SELL) {
                handleSell(order);
            }
        }
    }

    // ─── 合约持仓处理 ─────────────────────────────────────────

    private void handleFutures(Order order) {
        if (order.isReduceOnly()) {
            // 平仓操作
            if (order.getSide() == OrderSide.SELL) {
                // 平 LONG 仓
                Position existing = findOpenPosition(order.getStrategyId(), order.getAccountId(),
                        order.getExchange(), order.getSymbol(), PositionSide.LONG);
                if (existing == null) {
                    log.warn("[Futures] No open LONG to close for: {} {}", order.getExchange(), order.getSymbol());
                    return;
                }
                closeLongPosition(existing, order);
            } else {
                // BUY reduceOnly → 平 SHORT 仓
                Position existing = findOpenPosition(order.getStrategyId(), order.getAccountId(),
                        order.getExchange(), order.getSymbol(), PositionSide.SHORT);
                if (existing == null) {
                    log.warn("[Futures] No open SHORT to close for: {} {}", order.getExchange(), order.getSymbol());
                    return;
                }
                closeShortPosition(existing, order);
            }
        } else {
            // 开仓操作
            if (order.getSide() == OrderSide.BUY) {
                openLongPosition(order);
            } else {
                openShortPosition(order);
            }
        }
    }

    /**
     * 开 LONG 仓（合约）
     */
    private void openLongPosition(Order order) {
        Position existing = findOpenPosition(order.getStrategyId(), order.getAccountId(),
                order.getExchange(), order.getSymbol(), PositionSide.LONG);

        if (existing == null) {
            Position position = buildNewPosition(order, PositionSide.LONG);
            positionMapper.insert(position);
            log.info("[Futures] LONG opened: {} {} qty={} entry={}",
                    order.getExchange(), order.getSymbol(), position.getQuantity(), position.getEntryPrice());
        } else {
            // 加仓 — 更新均价
            averageIntoPosition(existing, order);
            log.info("[Futures] LONG increased: {} {} qty={} avgPrice={}",
                    order.getExchange(), order.getSymbol(), existing.getQuantity(), existing.getEntryPrice());
        }
    }

    /**
     * 开 SHORT 仓（合约）
     */
    private void openShortPosition(Order order) {
        Position existing = findOpenPosition(order.getStrategyId(), order.getAccountId(),
                order.getExchange(), order.getSymbol(), PositionSide.SHORT);

        if (existing == null) {
            Position position = buildNewPosition(order, PositionSide.SHORT);
            positionMapper.insert(position);
            log.info("[Futures] SHORT opened: {} {} qty={} entry={}",
                    order.getExchange(), order.getSymbol(), position.getQuantity(), position.getEntryPrice());
        } else {
            averageIntoPosition(existing, order);
            log.info("[Futures] SHORT increased: {} {} qty={} avgPrice={}",
                    order.getExchange(), order.getSymbol(), existing.getQuantity(), existing.getEntryPrice());
        }
    }

    /**
     * 平 LONG 仓（合约 reduceOnly SELL）
     * P&L = (closePrice - entryPrice) × qty - fee
     */
    private void closeLongPosition(Position existing, Order order) {
        BigDecimal sellQty  = order.getFilledQuantity();
        BigDecimal fee      = order.getFee() != null ? order.getFee() : BigDecimal.ZERO;
        BigDecimal grossPnl = order.getFilledPrice().subtract(existing.getEntryPrice()).multiply(sellQty);
        BigDecimal pnl      = grossPnl.subtract(fee);

        if (sellQty.compareTo(existing.getQuantity()) >= 0) {
            existing.setQuantity(BigDecimal.ZERO);
            existing.setRealizedPnl(existing.getRealizedPnl().add(pnl));
            existing.setUnrealizedPnl(BigDecimal.ZERO);
            existing.setCurrentPrice(order.getFilledPrice());
            existing.setClosePrice(order.getFilledPrice());
            existing.setClosedAt(LocalDateTime.now(ZoneOffset.UTC));
            existing.setStatus(PositionStatus.CLOSED);
            positionMapper.updateById(existing);
            log.info("[Futures] LONG closed: {} {} closePrice={} grossPnl={} fee={} netPnl={}",
                    order.getExchange(), order.getSymbol(), order.getFilledPrice(), grossPnl, fee, pnl);
        } else {
            existing.setQuantity(existing.getQuantity().subtract(sellQty));
            existing.setRealizedPnl(existing.getRealizedPnl().add(pnl));
            existing.setCurrentPrice(order.getFilledPrice());
            updateUnrealizedPnl(existing);
            positionMapper.updateById(existing);
            log.info("[Futures] LONG reduced: {} {} remainQty={} netPnl={}",
                    order.getExchange(), order.getSymbol(), existing.getQuantity(), pnl);
        }
    }

    /**
     * 平 SHORT 仓（合约 reduceOnly BUY）
     * P&L = (entryPrice - closePrice) × qty - fee  （价格下跌获利）
     */
    private void closeShortPosition(Position existing, Order order) {
        BigDecimal buyQty   = order.getFilledQuantity();
        BigDecimal fee      = order.getFee() != null ? order.getFee() : BigDecimal.ZERO;
        BigDecimal grossPnl = existing.getEntryPrice().subtract(order.getFilledPrice()).multiply(buyQty);
        BigDecimal pnl      = grossPnl.subtract(fee);

        if (buyQty.compareTo(existing.getQuantity()) >= 0) {
            existing.setQuantity(BigDecimal.ZERO);
            existing.setRealizedPnl(existing.getRealizedPnl().add(pnl));
            existing.setUnrealizedPnl(BigDecimal.ZERO);
            existing.setCurrentPrice(order.getFilledPrice());
            existing.setClosePrice(order.getFilledPrice());
            existing.setClosedAt(LocalDateTime.now(ZoneOffset.UTC));
            existing.setStatus(PositionStatus.CLOSED);
            positionMapper.updateById(existing);
            log.info("[Futures] SHORT closed: {} {} closePrice={} grossPnl={} fee={} netPnl={}",
                    order.getExchange(), order.getSymbol(), order.getFilledPrice(), grossPnl, fee, pnl);
        } else {
            existing.setQuantity(existing.getQuantity().subtract(buyQty));
            existing.setRealizedPnl(existing.getRealizedPnl().add(pnl));
            existing.setCurrentPrice(order.getFilledPrice());
            updateUnrealizedPnl(existing);
            positionMapper.updateById(existing);
            log.info("[Futures] SHORT reduced: {} {} remainQty={} netPnl={}",
                    order.getExchange(), order.getSymbol(), existing.getQuantity(), pnl);
        }
    }

    // ─── 现货持仓处理（不变，保持向后兼容）─────────────────

    private void handleBuy(Order order) {
        Position existing = findOpenPosition(order.getStrategyId(), order.getAccountId(),
                order.getExchange(), order.getSymbol());

        if (existing == null) {
            Position position = buildNewPosition(order, PositionSide.LONG);
            positionMapper.insert(position);
            log.info("Position opened: {} {} qty={} entry={}",
                    order.getExchange(), order.getSymbol(), position.getQuantity(), position.getEntryPrice());
        } else {
            averageIntoPosition(existing, order);
            log.info("Position increased: {} {} qty={} avgPrice={}",
                    order.getExchange(), order.getSymbol(), existing.getQuantity(), existing.getEntryPrice());
        }
    }

    private void handleSell(Order order) {
        Position existing = findOpenPosition(order.getStrategyId(), order.getAccountId(),
                order.getExchange(), order.getSymbol());

        if (existing == null) {
            log.warn("No open position found for sell order: {} {}", order.getExchange(), order.getSymbol());
            return;
        }

        BigDecimal sellQty  = order.getFilledQuantity();
        BigDecimal fee      = order.getFee() != null ? order.getFee() : BigDecimal.ZERO;
        BigDecimal grossPnl = order.getFilledPrice().subtract(existing.getEntryPrice()).multiply(sellQty);
        BigDecimal pnl      = grossPnl.subtract(fee);

        if (sellQty.compareTo(existing.getQuantity()) >= 0) {
            existing.setQuantity(BigDecimal.ZERO);
            existing.setRealizedPnl(existing.getRealizedPnl().add(pnl));
            existing.setUnrealizedPnl(BigDecimal.ZERO);
            existing.setCurrentPrice(order.getFilledPrice());
            existing.setClosePrice(order.getFilledPrice());
            existing.setClosedAt(LocalDateTime.now(ZoneOffset.UTC));
            existing.setStatus(PositionStatus.CLOSED);
            positionMapper.updateById(existing);
            log.info("Position closed: {} {} closePrice={} grossPnl={} fee={} netPnl={}",
                    order.getExchange(), order.getSymbol(), order.getFilledPrice(), grossPnl, fee, pnl);
        } else {
            existing.setQuantity(existing.getQuantity().subtract(sellQty));
            existing.setRealizedPnl(existing.getRealizedPnl().add(pnl));
            existing.setCurrentPrice(order.getFilledPrice());
            updateUnrealizedPnl(existing);
            positionMapper.updateById(existing);
            log.info("Position reduced: {} {} remainQty={} grossPnl={} fee={} netPnl={}",
                    order.getExchange(), order.getSymbol(), existing.getQuantity(), grossPnl, fee, pnl);
        }
    }

    // ─── 手动平仓 ─────────────────────────────────────────────

    /**
     * 手动平仓（支持 LONG / SHORT）
     * SHORT P&L 方向取反：(entryPrice - closePrice) × qty
     */
    public void closePosition(Position position, BigDecimal closePrice) {
        BigDecimal pnl;
        if (position.getSide() == PositionSide.SHORT) {
            pnl = position.getEntryPrice().subtract(closePrice).multiply(position.getQuantity());
        } else {
            pnl = closePrice.subtract(position.getEntryPrice()).multiply(position.getQuantity());
        }

        position.setQuantity(BigDecimal.ZERO);
        position.setCurrentPrice(closePrice);
        position.setClosePrice(closePrice);
        position.setClosedAt(LocalDateTime.now(ZoneOffset.UTC));
        position.setRealizedPnl(position.getRealizedPnl().add(pnl));
        position.setUnrealizedPnl(BigDecimal.ZERO);
        position.setStatus(PositionStatus.CLOSED);

        positionMapper.updateById(position);
        log.info("Position {} closed: {} {} closePrice={} pnl={}",
                position.getSide(), position.getExchange(), position.getSymbol(), closePrice, pnl);
    }

    // ─── 止盈止损检查 ─────────────────────────────────────────

    /**
     * 检查止盈止损（支持 LONG / SHORT）
     *
     * @return 是否触发了止盈止损
     */
    public boolean checkStopLossTakeProfit(Position position, BigDecimal currentPrice,
                                            BigDecimal stopLossPct, BigDecimal takeProfitPct) {
        if (position.getStatus() != PositionStatus.OPEN) {
            return false;
        }

        BigDecimal entryPrice = position.getEntryPrice();
        boolean isShort = position.getSide() == PositionSide.SHORT;

        // 止损检查
        if (stopLossPct != null && stopLossPct.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal stopLossPrice;
            boolean triggered;
            if (isShort) {
                // SHORT 止损：价格上涨超过 stopLossPct%
                stopLossPrice = entryPrice.multiply(
                        BigDecimal.ONE.add(stopLossPct.divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP)));
                triggered = currentPrice.compareTo(stopLossPrice) >= 0;
            } else {
                stopLossPrice = entryPrice.multiply(
                        BigDecimal.ONE.subtract(stopLossPct.divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP)));
                triggered = currentPrice.compareTo(stopLossPrice) <= 0;
            }
            if (triggered) {
                log.info("Stop loss triggered: {} {} {} currentPrice={} stopLossPrice={}",
                        position.getSide(), position.getExchange(), position.getSymbol(), currentPrice, stopLossPrice);
                closePosition(position, currentPrice);
                return true;
            }
        }

        // 止盈检查
        if (takeProfitPct != null && takeProfitPct.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal takeProfitPrice;
            boolean triggered;
            if (isShort) {
                // SHORT 止盈：价格下跌超过 takeProfitPct%
                takeProfitPrice = entryPrice.multiply(
                        BigDecimal.ONE.subtract(takeProfitPct.divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP)));
                triggered = currentPrice.compareTo(takeProfitPrice) <= 0;
            } else {
                takeProfitPrice = entryPrice.multiply(
                        BigDecimal.ONE.add(takeProfitPct.divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP)));
                triggered = currentPrice.compareTo(takeProfitPrice) >= 0;
            }
            if (triggered) {
                log.info("Take profit triggered: {} {} {} currentPrice={} takeProfitPrice={}",
                        position.getSide(), position.getExchange(), position.getSymbol(), currentPrice, takeProfitPrice);
                closePosition(position, currentPrice);
                return true;
            }
        }

        return false;
    }

    // ─── 持仓查询 ─────────────────────────────────────────────

    /**
     * 查找策略下的活跃持仓（不区分方向，现货常用）
     */
    public Position findOpenPosition(Long strategyId, Long accountId, String exchange, String symbol) {
        LambdaQueryWrapper<Position> wrapper = new LambdaQueryWrapper<Position>()
                .eq(Position::getStrategyId, strategyId)
                .eq(accountId != null, Position::getAccountId, accountId)
                .isNull(accountId == null, Position::getAccountId)
                .eq(Position::getExchange, exchange)
                .eq(Position::getSymbol, symbol)
                .eq(Position::getStatus, PositionStatus.OPEN)
                .eq(Position::getDeleted, 0)
                .last("LIMIT 1");
        return positionMapper.selectOne(wrapper);
    }

    /**
     * 查找策略下指定方向的活跃持仓（合约专用）
     */
    public Position findOpenPosition(Long strategyId, Long accountId, String exchange,
                                      String symbol, PositionSide side) {
        LambdaQueryWrapper<Position> wrapper = new LambdaQueryWrapper<Position>()
                .eq(Position::getStrategyId, strategyId)
                .eq(accountId != null, Position::getAccountId, accountId)
                .isNull(accountId == null, Position::getAccountId)
                .eq(Position::getExchange, exchange)
                .eq(Position::getSymbol, symbol)
                .eq(Position::getSide, side)
                .eq(Position::getStatus, PositionStatus.OPEN)
                .eq(Position::getDeleted, 0)
                .last("LIMIT 1");
        return positionMapper.selectOne(wrapper);
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

    /**
     * 查询指定策略的所有活跃持仓（用于移动止损更新）
     */
    public List<Position> findOpenPositionsByStrategy(Long strategyId) {
        LambdaQueryWrapper<Position> wrapper = new LambdaQueryWrapper<Position>()
                .eq(Position::getStrategyId, strategyId)
                .eq(Position::getStatus, PositionStatus.OPEN)
                .eq(Position::getDeleted, 0);
        return positionMapper.selectList(wrapper);
    }

    /**
     * 重新从数据库确认持仓仍处于 OPEN 状态（防止并发双重平仓）
     */
    public boolean isStillOpen(Long positionId) {
        Position fresh = positionMapper.selectById(positionId);
        return fresh != null && fresh.getStatus() == PositionStatus.OPEN;
    }

    /**
     * 更新持仓当前价格和未实现盈亏
     */
    public void updateCurrentPrice(Position position) {
        positionMapper.updateById(position);
    }

    /**
     * 更新持仓止盈止损价格
     */
    public void updateStopLossTakeProfit(Position position) {
        positionMapper.updateById(position);
        log.info("SL/TP updated: {} {} stopLoss={} takeProfit={}",
                position.getExchange(), position.getSymbol(),
                position.getStopLoss(), position.getTakeProfit());
    }

    /**
    /**
     * 计算策略所有已关闭仓位的累计已实现盈亏
     */
    public BigDecimal getTotalRealizedPnl(Long strategyId, Long accountId) {
        LambdaQueryWrapper<Position> wrapper = new LambdaQueryWrapper<Position>()
                .eq(Position::getStrategyId, strategyId)
                .eq(Position::getAccountId, accountId)
                .eq(Position::getStatus, PositionStatus.CLOSED)
                .eq(Position::getDeleted, 0);
        List<Position> closedPositions = positionMapper.selectList(wrapper);
        return closedPositions.stream()
                .map(p -> p.getRealizedPnl() != null ? p.getRealizedPnl() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * 计算策略当前持仓占用的资金（entryPrice × quantity 之和）
     */
    public BigDecimal getOccupiedCapital(Long strategyId, Long accountId) {
        LambdaQueryWrapper<Position> wrapper = new LambdaQueryWrapper<Position>()
                .eq(Position::getStrategyId, strategyId)
                .eq(Position::getAccountId, accountId)
                .eq(Position::getStatus, PositionStatus.OPEN)
                .eq(Position::getDeleted, 0);
        List<Position> openPositions = positionMapper.selectList(wrapper);
        return openPositions.stream()
                .map(p -> p.getEntryPrice().multiply(p.getQuantity()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    // ─── 私有辅助方法 ─────────────────────────────────────────

    private Position buildNewPosition(Order order, PositionSide side) {
        Position position = new Position();
        position.setStrategyId(order.getStrategyId());
        position.setAccountId(order.getAccountId());
        position.setExchange(order.getExchange());
        position.setSymbol(order.getSymbol());
        position.setSide(side);
        position.setQuantity(order.getFilledQuantity());
        position.setEntryPrice(order.getFilledPrice());
        position.setCurrentPrice(order.getFilledPrice());
        position.setUnrealizedPnl(BigDecimal.ZERO);

        // 合约手续费以报价资产（USDT）计量，不从数量扣减。
        // 开仓手续费记入初始已实现亏损，使 realizedPnl 从一开始就反映真实成本。
        // 现货 BUY 的手续费已在 filledQuantity 中扣减（少收到的币），此处无需重复计入。
        boolean isFutures = order.getMarketType() != null && order.getMarketType().isFutures();
        BigDecimal openFee = order.getFee() != null ? order.getFee() : BigDecimal.ZERO;
        position.setRealizedPnl(isFutures && openFee.compareTo(BigDecimal.ZERO) > 0
                ? openFee.negate()
                : BigDecimal.ZERO);

        position.setStatus(PositionStatus.OPEN);
        position.setTradeMode(order.getTradeMode());
        position.setMarketType(order.getMarketType());
        position.setLeverage(order.getLeverage());
        position.setMarginType(order.getMarginType());
        return position;
    }

    private void averageIntoPosition(Position existing, Order order) {
        BigDecimal totalQty  = existing.getQuantity().add(order.getFilledQuantity());
        BigDecimal totalCost = existing.getEntryPrice().multiply(existing.getQuantity())
                .add(order.getFilledPrice().multiply(order.getFilledQuantity()));
        BigDecimal newAvgPrice = totalCost.divide(totalQty, 10, RoundingMode.HALF_UP);

        existing.setQuantity(totalQty);
        existing.setEntryPrice(newAvgPrice);
        existing.setCurrentPrice(order.getFilledPrice());
        updateUnrealizedPnl(existing);
        positionMapper.updateById(existing);
    }

    /**
     * 更新未实现盈亏（LONG / SHORT 方向均支持）
     */
    private void updateUnrealizedPnl(Position position) {
        if (position.getCurrentPrice() != null && position.getEntryPrice() != null
                && position.getQuantity().compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal unrealized;
            if (position.getSide() == PositionSide.SHORT) {
                // SHORT：价格下跌获利
                unrealized = position.getEntryPrice().subtract(position.getCurrentPrice())
                        .multiply(position.getQuantity());
            } else {
                // LONG：价格上涨获利
                unrealized = position.getCurrentPrice().subtract(position.getEntryPrice())
                        .multiply(position.getQuantity());
            }
            position.setUnrealizedPnl(unrealized);
        }
    }
}

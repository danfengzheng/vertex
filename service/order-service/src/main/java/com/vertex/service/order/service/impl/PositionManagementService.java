package com.vertex.service.order.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.vertex.model.entity.strategy.Strategy;
import com.vertex.model.entity.trading.*;
import com.vertex.service.order.mapper.OrderMapper;
import com.vertex.service.order.mapper.PositionMapper;
import com.vertex.service.order.mapper.StrategyRefMapper;
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
    private final OrderMapper orderMapper;
    private final StrategyRefMapper strategyRefMapper;

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
            // 分阶段止盈：减仓单成交后推进 stage（CAS 防失序）
            if (order.getTakeProfitStage() != null && order.getTakeProfitStage() > 0) {
                advanceTakeProfitStage(existing.getId(), order.getTakeProfitStage());
            }
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
            // 分阶段止盈：减仓单成交后推进 stage（CAS 防失序）
            if (order.getTakeProfitStage() != null && order.getTakeProfitStage() > 0) {
                advanceTakeProfitStage(existing.getId(), order.getTakeProfitStage());
            }
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
            // 分阶段止盈：现货部分卖出（SELL 数量 < 持仓）成交后推进 stage
            if (order.getTakeProfitStage() != null && order.getTakeProfitStage() > 0) {
                advanceTakeProfitStage(existing.getId(), order.getTakeProfitStage());
            }
        }
    }

    // ─── 手动平仓 ─────────────────────────────────────────────

    /**
     * 手动 / 自动平仓（支持 LONG / SHORT）。
     * SHORT P&L 方向取反：(entryPrice - closePrice) × qty
     *
     * <p><b>DB 层 CAS 防双重平仓</b>：UPDATE 附加 {@code WHERE status='OPEN'} 条件，
     * 若另一线程已将状态改为 CLOSED，本次影响行数为 0，方法返回 {@code false} 并跳过后续结算，
     * 作为应用层 per-position 锁（{@link TradeExecutionService#executeClose}）的兜底保障。</p>
     *
     * @return {@code true} 表示本线程成功完成平仓；{@code false} 表示已被并发线程抢先关闭，调用方应跳过后续逻辑
     */
    public boolean closePosition(Position position, BigDecimal closePrice) {
        BigDecimal pnl;
        if (position.getSide() == PositionSide.SHORT) {
            pnl = position.getEntryPrice().subtract(closePrice).multiply(position.getQuantity());
        } else {
            pnl = closePrice.subtract(position.getEntryPrice()).multiply(position.getQuantity());
        }
        BigDecimal newRealizedPnl = position.getRealizedPnl().add(pnl);
        LocalDateTime closedAt = LocalDateTime.now(ZoneOffset.UTC);

        // 原子 CAS：WHERE id=? AND status='OPEN'
        // 确保数据库层只有一个线程能成功写入，防止并发双重结算
        int rows = positionMapper.update(null, new LambdaUpdateWrapper<Position>()
                .eq(Position::getId,     position.getId())
                .eq(Position::getStatus, PositionStatus.OPEN)      // ← CAS 守卫：仅 OPEN 状态允许平仓
                .set(Position::getQuantity,      BigDecimal.ZERO)
                .set(Position::getCurrentPrice,  closePrice)
                .set(Position::getClosePrice,    closePrice)
                .set(Position::getClosedAt,      closedAt)
                .set(Position::getRealizedPnl,   newRealizedPnl)
                .set(Position::getUnrealizedPnl, BigDecimal.ZERO)
                .set(Position::getStatus,        PositionStatus.CLOSED));

        if (rows == 0) {
            log.info("[Close] Position {} CAS failed – already closed by concurrent thread, skipping",
                    position.getId());
            return false;
        }

        // 同步更新内存对象，供 isPositionAtLoss() 等上层方法读取最新 realizedPnl / status
        position.setQuantity(BigDecimal.ZERO);
        position.setCurrentPrice(closePrice);
        position.setClosePrice(closePrice);
        position.setClosedAt(closedAt);
        position.setRealizedPnl(newRealizedPnl);
        position.setUnrealizedPnl(BigDecimal.ZERO);
        position.setStatus(PositionStatus.CLOSED);

        log.info("Position {} closed: {} {} closePrice={} pnl={}",
                position.getSide(), position.getExchange(), position.getSymbol(), closePrice, pnl);
        return true;
    }

    // ─── 止盈止损检查 ─────────────────────────────────────────

    /**
     * 检查止盈止损（支持 LONG / SHORT）
     *
     * <p><b>⚠️ 已废弃，请勿调用：</b></p>
     * <ul>
     *   <li>此方法直接调用 {@link #closePosition}（PAPER 式关单），不提交交易所平仓订单，
     *       若用于 LIVE 持仓会造成 DB 标记 CLOSED 而交易所仓位仍开着的数据不一致。</li>
     *   <li>当前 {@link com.vertex.service.order.task.StopLossTakeProfitTask} 已改为通过
     *       {@link com.vertex.service.order.service.impl.TradeExecutionService#executeStopLossClose}
     *       执行平仓，此方法为未被调用的历史遗留代码。</li>
     * </ul>
     *
     * @return 是否触发了止盈止损
     * @deprecated 已由 TradeExecutionService.executeStopLossClose() 替代，请勿新增调用
     */
    @Deprecated
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
     * 读取持仓（含已关闭），供分阶段止盈在锁内复查 stage 用。
     */
    public Position getById(Long positionId) {
        return positionMapper.selectById(positionId);
    }

    /**
     * 读取持仓当前的 takeProfitStage（DB 视角）。
     */
    public Integer getTakeProfitStage(Long positionId) {
        Position fresh = positionMapper.selectById(positionId);
        return fresh == null ? null : fresh.getTakeProfitStage();
    }

    /**
     * 是否存在该持仓未结算的减仓订单（SUBMITTED / PARTIALLY_FILLED）。
     * 用于分阶段止盈和全平的 inflight 防重：
     * 若上一档/上一笔平仓订单还在路上，本轮跳过避免重复下单。
     */
    public boolean hasInflightReduceOrder(Position position) {
        MarketType mt = position.getMarketType();
        boolean isFutures = mt != null && mt.isFutures();
        OrderSide reduceSide = (isFutures && position.getSide() == PositionSide.SHORT)
                ? OrderSide.BUY : OrderSide.SELL;
        Long count = orderMapper.selectCount(new LambdaQueryWrapper<Order>()
                .eq(Order::getStrategyId, position.getStrategyId())
                .eq(Order::getExchange,  position.getExchange())
                .eq(Order::getSymbol,    position.getSymbol())
                .eq(Order::getSide,      reduceSide)
                .in(Order::getStatus,
                        OrderStatus.SUBMITTED, OrderStatus.PARTIALLY_FILLED));
        return count != null && count > 0;
    }

    /**
     * 分阶段止盈 PAPER 路径的部分减仓。
     * <p>
     * 仅在 PAPER 模式下直接调用：写减仓量、累加 realizedPnl、推进 takeProfitStage（CAS）、
     * 同步更新 takeProfit 字段为下一档触发价（runner 模式 / 末档已扫尾时写 null）。
     * LIVE 模式则通过 reduceOnly 单走 fill 回调（{@link #updatePosition(Order)} 的减仓分支）。
     * </p>
     */
    public void reducePosition(Position position, BigDecimal closeQty,
                                BigDecimal closePrice, int targetStage) {
        BigDecimal pnl;
        if (position.getSide() == PositionSide.SHORT) {
            pnl = position.getEntryPrice().subtract(closePrice).multiply(closeQty);
        } else {
            pnl = closePrice.subtract(position.getEntryPrice()).multiply(closeQty);
        }
        BigDecimal newRealizedPnl = position.getRealizedPnl() == null
                ? pnl : position.getRealizedPnl().add(pnl);
        BigDecimal remain = position.getQuantity().subtract(closeQty);
        BigDecimal nextTakeProfit = computeNextTakeProfit(position, targetStage);

        int rows = positionMapper.update(null, new LambdaUpdateWrapper<Position>()
                .eq(Position::getId, position.getId())
                .eq(Position::getStatus, PositionStatus.OPEN)
                .eq(Position::getTakeProfitStage, targetStage - 1)
                .set(Position::getQuantity,        remain)
                .set(Position::getCurrentPrice,    closePrice)
                .set(Position::getRealizedPnl,     newRealizedPnl)
                .set(Position::getTakeProfitStage, targetStage)
                .set(Position::getTakeProfit,      nextTakeProfit));
        if (rows == 0) {
            log.info("[PartialClose PAPER] CAS failed for position {} stage {} (already advanced?), skip",
                    position.getId(), targetStage);
            return;
        }

        position.setQuantity(remain);
        position.setCurrentPrice(closePrice);
        position.setRealizedPnl(newRealizedPnl);
        position.setTakeProfitStage(targetStage);
        position.setTakeProfit(nextTakeProfit);
        updateUnrealizedPnl(position);

        log.info("[PartialClose PAPER] position={} stage={} closeQty={} closePrice={} pnl={} remain={} nextTp={}",
                position.getId(), targetStage, closeQty, closePrice, pnl, remain, nextTakeProfit);
    }

    /**
     * 推进持仓的 takeProfitStage（在 LIVE reduceOnly 单成交后调用），
     * 同时把 takeProfit 字段同步更新为下一档触发价（或末档 runner 模式时写 null）。
     * 使用 CAS（WHERE take_profit_stage = targetStage - 1）防止 fill 回调失序。
     */
    public void advanceTakeProfitStage(Long positionId, int targetStage) {
        Position fresh = positionMapper.selectById(positionId);
        if (fresh == null) return;
        BigDecimal nextTakeProfit = computeNextTakeProfit(fresh, targetStage);
        int rows = positionMapper.update(null, new LambdaUpdateWrapper<Position>()
                .eq(Position::getId, positionId)
                .eq(Position::getTakeProfitStage, targetStage - 1)
                .set(Position::getTakeProfitStage, targetStage)
                .set(Position::getTakeProfit,      nextTakeProfit));
        if (rows == 0) {
            log.info("[Staged TP] advanceTakeProfitStage CAS failed: position={} target={} (likely already advanced)",
                    positionId, targetStage);
        } else {
            log.info("[Staged TP] position={} stage advanced to {} nextTp={}",
                    positionId, targetStage, nextTakeProfit);
        }
    }

    /**
     * 计算分阶段止盈推进到 newStage 之后，下一档（newStage+1）的触发价。
     * <ul>
     *   <li>仍有后续档：返回基于入场价计算的下一档 trigger（LONG: entry×(1+pct%)，SHORT: entry×(1-pct%)）。</li>
     *   <li>已是最后已配置档：返回 null（runner 模式或全平后，DB 中 takeProfit 字段清空）。</li>
     * </ul>
     */
    private BigDecimal computeNextTakeProfit(Position position, int newStage) {
        if (position.getStrategyId() == null) return null;
        Strategy s = strategyRefMapper.selectById(position.getStrategyId());
        if (s == null) return null;
        BigDecimal nextPct;
        switch (newStage) {
            case 1 -> nextPct = isPositive(s.getTakeProfitSize2()) ? s.getTakeProfitPct2() : null;
            case 2 -> nextPct = isPositive(s.getTakeProfitSize3()) ? s.getTakeProfitPct3() : null;
            default -> nextPct = null; // newStage >= 3 → 无后续档
        }
        if (nextPct == null || nextPct.compareTo(BigDecimal.ZERO) <= 0) return null;
        BigDecimal entry = position.getEntryPrice();
        if (entry == null) return null;
        BigDecimal pct = nextPct.divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP);
        boolean isShort = position.getSide() == PositionSide.SHORT;
        BigDecimal next = isShort
                ? entry.multiply(BigDecimal.ONE.subtract(pct))
                : entry.multiply(BigDecimal.ONE.add(pct));
        return next.setScale(8, RoundingMode.HALF_UP);
    }

    private static boolean isPositive(BigDecimal v) {
        return v != null && v.compareTo(BigDecimal.ZERO) > 0;
    }

    /**
     * 更新持仓当前价格、未实现盈亏以及峰值价格（highestPrice / lowestPrice）。
     * <p>
     * 使用列级精确更新，只写入本方法关心的字段，
     * 避免与 {@link #updateSuperTrendStopLoss} 等并发更新产生 {@code updateById} 全量覆盖竞态。
     * </p>
     */
    public void updateCurrentPrice(Position position) {
        positionMapper.update(null, new LambdaUpdateWrapper<Position>()
                .eq(Position::getId, position.getId())
                .set(Position::getCurrentPrice,  position.getCurrentPrice())
                .set(Position::getUnrealizedPnl, position.getUnrealizedPnl())
                .set(Position::getHighestPrice,  position.getHighestPrice())
                .set(Position::getLowestPrice,   position.getLowestPrice()));
    }

    /**
     * 更新持仓止盈止损相关字段（列级精确写入）。
     * <p>
     * 只更新止损止盈价、止损阶段、极值追踪价、K线计数，
     * 避免 {@code updateById} 全量覆盖并发写入的
     * {@code superTrendStopLoss}、{@code currentPrice} 等字段。
     * </p>
     */
    public void updateStopLossTakeProfit(Position position) {
        positionMapper.update(null, new LambdaUpdateWrapper<Position>()
                .eq(Position::getId, position.getId())
                .set(Position::getStopLoss,        position.getStopLoss())
                .set(Position::getTakeProfit,      position.getTakeProfit())
                .set(Position::getStopLossStage,   position.getStopLossStage())
                .set(Position::getHighestPrice,    position.getHighestPrice())
                .set(Position::getLowestPrice,     position.getLowestPrice())
                .set(Position::getOpenBarCount,    position.getOpenBarCount())
                .set(Position::getTakeProfitStage, position.getTakeProfitStage())
                .set(Position::getInitialQuantity, position.getInitialQuantity()));
        log.info("SL/TP updated: {} {} stopLoss={} takeProfit={} stage={} initialQty={}",
                position.getExchange(), position.getSymbol(),
                position.getStopLoss(), position.getTakeProfit(),
                position.getTakeProfitStage(), position.getInitialQuantity());
    }

    /**
     * 仅更新 open_bar_count（K线计数），避免全量 updateById 覆盖其他并发写入的字段。
     */
    public void updateOpenBarCount(Position position) {
        positionMapper.update(null, new LambdaUpdateWrapper<Position>()
                .eq(Position::getId, position.getId())
                .set(Position::getOpenBarCount, position.getOpenBarCount()));
    }

    /**
     * 精准更新 SuperTrend 动态止损价（仅更新 super_trend_stop_loss 列，不影响其他字段）。
     * <p>
     * 使用 {@code LambdaUpdateWrapper} 做列级更新，避免 {@code updateById} 全量覆盖
     * 导致与 {@link #updateCurrentPrice} 等并发更新产生竞态写入。
     * </p>
     */
    public void updateSuperTrendStopLoss(Position position) {
        positionMapper.update(null, new LambdaUpdateWrapper<Position>()
                .eq(Position::getId, position.getId())
                .set(Position::getSuperTrendStopLoss, position.getSuperTrendStopLoss()));
        log.debug("[SuperTrend SL] Persisted position={} superTrendStopLoss={}",
                position.getId(), position.getSuperTrendStopLoss());
    }

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
        // 分阶段止盈基准量：记录开仓时的原始数量，作为各档平仓量分母
        position.setInitialQuantity(order.getFilledQuantity());
        position.setTakeProfitStage(0);

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
        // 加仓时重置分阶段止盈基准：initialQuantity 取新合计量，takeProfitStage 清零；
        // 后续 setStopLossTakeProfit 会基于新均价重新计算 TP1 触发价。
        existing.setInitialQuantity(totalQty);
        existing.setTakeProfitStage(0);
        updateUnrealizedPnl(existing);

        // 列级精确更新：只写均价相关字段，避免 updateById 全量覆盖并发写入的
        // superTrendStopLoss / stopLossStage / openBarCount 等字段
        positionMapper.update(null, new LambdaUpdateWrapper<Position>()
                .eq(Position::getId, existing.getId())
                .set(Position::getQuantity,        existing.getQuantity())
                .set(Position::getEntryPrice,      existing.getEntryPrice())
                .set(Position::getCurrentPrice,    existing.getCurrentPrice())
                .set(Position::getUnrealizedPnl,   existing.getUnrealizedPnl())
                .set(Position::getInitialQuantity, existing.getInitialQuantity())
                .set(Position::getTakeProfitStage, existing.getTakeProfitStage()));
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

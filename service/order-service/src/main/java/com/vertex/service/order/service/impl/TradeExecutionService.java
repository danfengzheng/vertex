package com.vertex.service.order.service.impl;

import com.alibaba.fastjson2.JSONObject;
import com.vertex.common.core.GlobalError;
import com.vertex.common.core.exception.BizException;
import com.vertex.model.entity.strategy.Signal;
import com.vertex.model.entity.strategy.SignalType;
import com.vertex.model.entity.strategy.Strategy;
import com.vertex.model.entity.trading.*;
import com.vertex.service.order.client.BinanceTradeClient;
import com.vertex.service.order.mapper.OrderMapper;
import com.vertex.service.order.notify.CompositeTradeNotifier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * 核心交易执行器
 * <p>
 * 收到信号后根据策略配置决定执行方式：
 * - tradeMode=AUTO → 直接执行
 * - tradeMode=MANUAL → 创建 PENDING 订单 → WebSocket 通知 → 等待用户确认
 * - executionMode=PAPER → 模拟交易
 * - executionMode=LIVE → 调用 Binance API
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TradeExecutionService {

    private final OrderMapper orderMapper;
    private final ExchangeAccountServiceImpl accountService;
    private final PaperTradingService paperTradingService;
    private final PositionManagementService positionManagementService;
    private final BinanceTradeClient binanceTradeClient;
    private final CompositeTradeNotifier compositeTradeNotifier;

    /**
     * 信号触发的交易执行入口
     */
    public void executeSignal(Strategy strategy, Signal signal) {
        // 仅处理 BUY/SELL 信号
        if (signal.getSignalType() == SignalType.NEUTRAL) {
            return;
        }

        // 持仓检查：避免重复开仓或无仓位平仓
        Position openPosition = positionManagementService.findOpenPosition(
                strategy.getId(), strategy.getAccountId(),
                strategy.getExchange(), strategy.getSymbol());

        if (openPosition != null && signal.getSignalType() == SignalType.BUY) {
            log.debug("Strategy [{}] already has open position for {} {}, ignoring BUY signal",
                    strategy.getName(), strategy.getExchange(), strategy.getSymbol());
            return;
        }

        if (openPosition == null && signal.getSignalType() == SignalType.SELL) {
            log.debug("Strategy [{}] has no open position for {} {}, ignoring SELL signal",
                    strategy.getName(), strategy.getExchange(), strategy.getSymbol());
            return;
        }

        OrderSide side = signal.getSignalType() == SignalType.BUY ? OrderSide.BUY : OrderSide.SELL;

        // 创建订单
        Order order = new Order();
        order.setStrategyId(strategy.getId());
        order.setAccountId(strategy.getAccountId());
        order.setSignalId(signal.getId());
        order.setExchange(strategy.getExchange());
        order.setSymbol(strategy.getSymbol());
        order.setSide(side);
        order.setOrderType(OrderType.MARKET);
        order.setQuantity(strategy.getTradeQuantity());
        order.setTradeMode(strategy.getExecutionMode());

        if (order.getQuantity() == null || order.getQuantity().compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("Strategy [{}] has invalid trade quantity", strategy.getName());
            return;
        }

        TradeMode tradeMode = strategy.getTradeMode();
        if (tradeMode == null) {
            tradeMode = TradeMode.AUTO;
        }

        if (tradeMode == TradeMode.MANUAL) {
            // MANUAL 模式: 创建 PENDING 订单，等待用户确认
            order.setStatus(OrderStatus.PENDING);
            orderMapper.insert(order);

            compositeTradeNotifier.notifyOrderCreated(order, strategy);

            log.info("Pending order created for strategy [{}]: {} {} {}",
                    strategy.getName(), side, strategy.getSymbol(), order.getQuantity());
        } else {
            // AUTO 模式: 直接执行
            order.setStatus(OrderStatus.SUBMITTED);
            orderMapper.insert(order);

            compositeTradeNotifier.notifyOrderCreated(order, strategy);

            doExecute(order, strategy);
        }
    }

    /**
     * 确认 PENDING 订单（MANUAL 模式）
     */
    public void confirmOrder(Long orderId, Strategy strategy) {
        Order order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new BizException(GlobalError.TRADE_ORDER_NOT_FOUND);
        }
        if (order.getStatus() != OrderStatus.PENDING) {
            throw new BizException(GlobalError.TRADE_ORDER_CANNOT_CANCEL);
        }
        order.setStatus(OrderStatus.SUBMITTED);
        orderMapper.updateById(order);
        doExecute(order, strategy);
    }

    /**
     * 拒绝 PENDING 订单
     */
    public void rejectOrder(Long orderId) {
        Order order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new BizException(GlobalError.TRADE_ORDER_NOT_FOUND);
        }
        if (order.getStatus() != OrderStatus.PENDING) {
            throw new BizException(GlobalError.TRADE_ORDER_CANNOT_CANCEL);
        }
        order.setStatus(OrderStatus.REJECTED);
        orderMapper.updateById(order);
    }

    /**
     * 实际执行交易
     */
    private void doExecute(Order order, Strategy strategy) {
        try {
            if (order.getTradeMode() == ExecutionMode.PAPER) {
                // 模拟交易 — 传递手续费率与回测对齐
                BigDecimal feeRate = strategy != null ? strategy.getFeeRate() : null;
                paperTradingService.simulateFill(order, feeRate);
            } else {
                // 实盘交易
                executeLive(order);
            }

            orderMapper.updateById(order);

            // 成交后更新持仓
            if (order.getStatus() == OrderStatus.FILLED || order.getStatus() == OrderStatus.SIMULATED) {
                positionManagementService.updatePosition(order);

                // 设置止盈止损（如果策略配置了）
                if (strategy != null && order.getSide() == OrderSide.BUY) {
                    setStopLossTakeProfit(order, strategy);
                }
            }

            // 通知所有渠道（WebSocket、Telegram 等）
            compositeTradeNotifier.notifyOrderFilled(order);

        } catch (Exception e) {
            order.setStatus(OrderStatus.REJECTED);
            order.setErrorMsg(e.getMessage());
            orderMapper.updateById(order);
            log.error("Trade execution failed for order {}: {}", order.getId(), e.getMessage(), e);
        }
    }

    /**
     * 实盘执行
     */
    private void executeLive(Order order) {
        String[] credentials = accountService.getDecryptedCredentials(order.getAccountId());
        String apiKey = credentials[0];
        String apiSecret = credentials[1];

        JSONObject result = binanceTradeClient.placeOrder(
                apiKey, apiSecret,
                order.getSymbol(),
                order.getSide().name(),
                order.getOrderType().name(),
                order.getQuantity(),
                order.getPrice()
        );

        // 解析交易所返回
        order.setExchangeOrderId(result.getString("orderId"));
        String status = result.getString("status");

        if ("FILLED".equals(status)) {
            order.setStatus(OrderStatus.FILLED);
            order.setFilledQuantity(result.getBigDecimal("executedQty"));
            // 计算加权平均成交价
            BigDecimal cummQuoteQty = result.getBigDecimal("cummulativeQuoteQty");
            BigDecimal executedQty = result.getBigDecimal("executedQty");
            if (executedQty != null && executedQty.compareTo(BigDecimal.ZERO) > 0) {
                order.setFilledPrice(cummQuoteQty.divide(executedQty, 10, java.math.RoundingMode.HALF_UP));
            }
        } else if ("PARTIALLY_FILLED".equals(status)) {
            order.setStatus(OrderStatus.PARTIALLY_FILLED);
            order.setFilledQuantity(result.getBigDecimal("executedQty"));
        } else if ("NEW".equals(status)) {
            order.setStatus(OrderStatus.SUBMITTED);
        } else {
            order.setStatus(OrderStatus.REJECTED);
            order.setErrorMsg("Exchange status: " + status);
        }
    }

    /**
     * 为新开仓设置止盈止损
     */
    private void setStopLossTakeProfit(Order order, Strategy strategy) {
        if (strategy.getStopLossPct() == null && strategy.getTakeProfitPct() == null) {
            return;
        }

        Position position = positionManagementService.findOpenPosition(
                order.getStrategyId(), order.getAccountId(), order.getExchange(), order.getSymbol());

        if (position != null) {
            BigDecimal entryPrice = position.getEntryPrice();
            if (strategy.getStopLossPct() != null) {
                BigDecimal stopLoss = entryPrice.multiply(
                        BigDecimal.ONE.subtract(strategy.getStopLossPct().divide(BigDecimal.valueOf(100), 10, java.math.RoundingMode.HALF_UP)));
                position.setStopLoss(stopLoss);
            }
            if (strategy.getTakeProfitPct() != null) {
                BigDecimal takeProfit = entryPrice.multiply(
                        BigDecimal.ONE.add(strategy.getTakeProfitPct().divide(BigDecimal.valueOf(100), 10, java.math.RoundingMode.HALF_UP)));
                position.setTakeProfit(takeProfit);
            }
            // 持久化止盈止损到数据库
            positionManagementService.updateStopLossTakeProfit(position);
        }
    }

}

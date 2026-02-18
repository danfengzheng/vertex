package com.vertex.service.order.service.impl;

import com.alibaba.fastjson2.JSONObject;
import com.vertex.common.core.GlobalError;
import com.vertex.common.core.exception.BizException;
import com.vertex.model.entity.strategy.Signal;
import com.vertex.model.entity.strategy.SignalType;
import com.vertex.model.entity.strategy.Strategy;
import com.vertex.model.entity.trading.*;
import com.vertex.service.order.client.BinanceTradeClient;
import com.vertex.service.order.config.TradingProperties;
import com.vertex.service.order.mapper.OrderMapper;
import com.vertex.service.order.notify.CompositeTradeNotifier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import com.alibaba.fastjson2.JSONArray;

import java.math.BigDecimal;
import java.math.RoundingMode;

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
    private final TradingProperties tradingProperties;

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

        // 确定下单数量：卖出时使用持仓数量（避免因手续费扣减导致余额不足），买入时使用策略配置
        BigDecimal quantity;
        if (side == OrderSide.SELL && openPosition != null) {
            quantity = openPosition.getQuantity();
        } else {
            quantity = strategy.getTradeQuantity();
        }

        if (quantity == null || quantity.compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("Strategy [{}] has invalid trade quantity", strategy.getName());
            return;
        }

        // 创建订单
        Order order = new Order();
        order.setStrategyId(strategy.getId());
        order.setAccountId(strategy.getAccountId());
        order.setSignalId(signal.getId());
        order.setExchange(strategy.getExchange());
        order.setSymbol(strategy.getSymbol());
        order.setSide(side);
        order.setOrderType(OrderType.MARKET);
        order.setQuantity(quantity);
        order.setPrice(signal.getPrice());  // 记录信号触发价格，用于滑点校验
        order.setTradeMode(strategy.getExecutionMode());

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
                // 实盘交易：滑点保护 → 执行
                if (applySlippageProtection(order)) {
                    executeLive(order);
                }
                // applySlippageProtection 返回 false 时已设置 REJECTED 状态
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
     * 滑点保护：检查当前市价与信号价偏差，通过后转换为 LIMIT 单
     *
     * @return true 允许执行，false 拒绝（order 已设为 REJECTED）
     */
    private boolean applySlippageProtection(Order order) {
        TradingProperties.Slippage config = tradingProperties.getSlippage();
        if (config == null || !config.isEnabled()) {
            return true;  // 未启用，保持 MARKET 单原有行为
        }

        // 1. 获取当前市价
        BigDecimal currentPrice = paperTradingService.getCurrentPrice(
                order.getExchange(), order.getSymbol());
        if (currentPrice == null) {
            log.warn("[Slippage] No price data for {} {}, falling back to MARKET order",
                    order.getExchange(), order.getSymbol());
            return true;  // 无价格数据时降级为 MARKET
        }

        // 2. 滑点检查：比较信号价格 vs 当前市价
        BigDecimal signalPrice = order.getPrice();
        if (signalPrice != null && signalPrice.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal deviation = currentPrice.subtract(signalPrice).abs()
                    .divide(signalPrice, 6, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100));

            if (deviation.compareTo(config.getMaxSlippagePct()) > 0) {
                log.warn("[Slippage] Price deviation {}% exceeds max {}% (signal={}, current={}), rejecting order",
                        deviation, config.getMaxSlippagePct(), signalPrice, currentPrice);
                order.setStatus(OrderStatus.REJECTED);
                order.setErrorMsg(String.format("价格偏差 %s%% 超过阈值 %s%% (信号价: %s, 当前价: %s)",
                        deviation.setScale(2, RoundingMode.HALF_UP),
                        config.getMaxSlippagePct(),
                        signalPrice, currentPrice));
                return false;
            }
        }

        // 3. 转换为 LIMIT 单
        BigDecimal limitPricePct = config.getLimitPricePct()
                .divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP);

        BigDecimal limitPrice;
        if (order.getSide() == OrderSide.BUY) {
            // 买入限价 = 当前市价 × (1 + pct)，允许略高于市价买入
            limitPrice = currentPrice.multiply(BigDecimal.ONE.add(limitPricePct));
        } else {
            // 卖出限价 = 当前市价 × (1 - pct)，允许略低于市价卖出
            limitPrice = currentPrice.multiply(BigDecimal.ONE.subtract(limitPricePct));
        }

        order.setOrderType(OrderType.LIMIT);
        order.setPrice(limitPrice.setScale(getPriceScale(currentPrice), RoundingMode.HALF_UP));

        log.info("[Slippage] Converted to LIMIT order: {} {} price={} (current={}, signal={})",
                order.getSide(), order.getSymbol(), order.getPrice(), currentPrice, signalPrice);
        return true;
    }

    /**
     * 根据价格量级决定小数位精度（Binance 对不同币种有不同精度要求）
     */
    private int getPriceScale(BigDecimal price) {
        if (price.compareTo(BigDecimal.valueOf(10)) < 0) return 4;
        if (price.compareTo(BigDecimal.valueOf(1000)) < 0) return 2;
        return 2;
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
            BigDecimal executedQty = result.getBigDecimal("executedQty");
            BigDecimal cummQuoteQty = result.getBigDecimal("cummulativeQuoteQty");

            // 计算加权平均成交价
            if (executedQty != null && executedQty.compareTo(BigDecimal.ZERO) > 0) {
                order.setFilledPrice(cummQuoteQty.divide(executedQty, 10, RoundingMode.HALF_UP));
            }

            // 从 fills 数组中提取手续费，计算实际到账量
            // Binance MARKET 买单默认从买入的币中扣手续费（除非用 BNB 抵扣）
            BigDecimal totalFee = BigDecimal.ZERO;
            boolean feeInBaseAsset = false;
            JSONArray fills = result.getJSONArray("fills");
            if (fills != null && !fills.isEmpty()) {
                // 判断手续费币种：取第一笔 fill 的 commissionAsset
                String baseAsset = extractBaseAsset(order.getSymbol());
                for (int j = 0; j < fills.size(); j++) {
                    JSONObject fill = fills.getJSONObject(j);
                    BigDecimal commission = fill.getBigDecimal("commission");
                    String commissionAsset = fill.getString("commissionAsset");
                    if (commission != null) {
                        totalFee = totalFee.add(commission);
                        if (baseAsset != null && baseAsset.equalsIgnoreCase(commissionAsset)) {
                            feeInBaseAsset = true;
                        }
                    }
                }
            }
            order.setFee(totalFee);

            // 如果手续费从买入的币中扣除（BUY 且手续费币种 = 基础资产），实际到账量需扣减
            if (order.getSide() == OrderSide.BUY && feeInBaseAsset) {
                order.setFilledQuantity(executedQty.subtract(totalFee));
                log.info("[Live Trade] BUY fee deducted from base asset: executedQty={}, fee={}, actualQty={}",
                        executedQty, totalFee, order.getFilledQuantity());
            } else {
                order.setFilledQuantity(executedQty);
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
    /**
     * 从交易对中提取基础资产（如 BTC-USDT → BTC）
     */
    private String extractBaseAsset(String symbol) {
        if (symbol == null) return null;
        int idx = symbol.indexOf('-');
        return idx > 0 ? symbol.substring(0, idx).toUpperCase() : symbol.toUpperCase();
    }

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

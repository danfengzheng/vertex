package com.vertex.service.order.service.impl;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.vertex.common.core.GlobalError;
import com.vertex.common.core.exception.BizException;
import com.vertex.model.entity.quote.KLine;
import com.vertex.model.entity.quote.KLineInterval;
import com.vertex.model.entity.strategy.Signal;
import com.vertex.model.entity.strategy.SignalType;
import com.vertex.model.entity.strategy.Strategy;
import com.vertex.model.entity.trading.*;
import com.vertex.service.order.client.BinanceFuturesClient;
import com.vertex.service.order.client.BinanceTradeClient;
import com.vertex.service.order.config.TradingProperties;
import com.vertex.service.order.mapper.OrderMapper;
import com.vertex.service.order.mapper.StrategyRefMapper;
import com.vertex.service.order.notify.CompositeTradeNotifier;
import com.vertex.service.quote.store.KLineStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 核心交易执行器
 * <p>
 * 收到信号后根据策略配置决定执行方式：
 * - tradeMode=AUTO → 直接执行
 * - tradeMode=MANUAL → 创建 PENDING 订单 → WebSocket 通知 → 等待用户确认
 * - executionMode=PAPER → 模拟交易
 * - executionMode=LIVE → 调用 Binance API
 * <p>
 * 合约交易（USDM / COINM）额外支持：
 * - 单向持仓模式：BUY 信号先平空再开多；SELL 信号先平多再开空
 * - 开仓前自动设置杠杆和保证金模式（幂等安全）
 * - 平仓使用 reduceOnly=true 防止意外反向开仓
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TradeExecutionService {

    private final OrderMapper orderMapper;
    private final StrategyRefMapper strategyRefMapper;
    private final ExchangeAccountServiceImpl accountService;
    private final PaperTradingService paperTradingService;
    private final PositionManagementService positionManagementService;
    private final BinanceTradeClient binanceTradeClient;
    private final BinanceFuturesClient binanceFuturesClient;
    private final CompositeTradeNotifier compositeTradeNotifier;
    private final TradingProperties tradingProperties;
    private final KLineStore klineStore;

    /**
     * 合约账户参数缓存：key = accountId_symbol_marketType，避免重复调用 Binance 设置杠杆/保证金模式。
     * 服务重启后缓存清空，第一次开仓时仍会同步一次，确保状态正确。
     */
    private final ConcurrentHashMap<String, Integer> leverageCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, MarginType> marginTypeCache = new ConcurrentHashMap<>();

    /**
     * 同一策略的委托必须串行执行，防止并发导致重复开仓。
     */
    private final ConcurrentHashMap<Long, ReentrantLock> strategyLocks = new ConcurrentHashMap<>();

    private ReentrantLock getLockForStrategy(Long strategyId) {
        return strategyLocks.computeIfAbsent(strategyId, id -> new ReentrantLock(true));
    }

    // ─── 信号执行入口 ─────────────────────────────────────────

    public void executeSignal(Strategy strategy, Signal signal) {
        if (signal.getSignalType() == SignalType.NEUTRAL) {
            return;
        }
        ReentrantLock lock = getLockForStrategy(strategy.getId());
        lock.lock();
        try {
            executeSignalInternal(strategy, signal);
        } finally {
            lock.unlock();
        }
    }

    private void executeSignalInternal(Strategy strategy, Signal signal) {
        MarketType marketType = getAccountMarketType(strategy.getAccountId());
        if (marketType != null && marketType.isFutures()) {
            executeSignalFutures(strategy, signal, marketType);
        } else {
            executeSignalSpot(strategy, signal);
        }
    }

    // ─── 现货信号处理 ─────────────────────────────────────────

    private void executeSignalSpot(Strategy strategy, Signal signal) {
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

        // 日亏损熔断：无持仓时的 BUY（开仓）被暂停拦截；持仓 SELL（平仓）不受限
        if (openPosition == null && signal.getSignalType() == SignalType.BUY
                && isStrategyTradingPaused(strategy)) {
            log.info("[StopLossPause] Strategy [{}] trading paused until {} UTC, skip BUY open",
                    strategy.getName(), strategy.getTradingPausedUntil());
            return;
        }

        OrderSide side = signal.getSignalType() == SignalType.BUY ? OrderSide.BUY : OrderSide.SELL;
        BigDecimal quantity;
        if (side == OrderSide.SELL && openPosition != null) {
            quantity = openPosition.getQuantity();
        } else {
            quantity = calculateBuyQuantity(strategy, signal);
        }

        if (quantity == null || quantity.compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("Strategy [{}] has invalid trade quantity", strategy.getName());
            return;
        }
        submitOpenOrder(strategy, signal, side, quantity, false, null);
    }

    // ─── 合约信号处理（单向持仓模式）────────────────────────

    /**
     * 合约信号处理（One-Way Mode）：
     * BUY  → 先平 SHORT（若有），再开 LONG（若无）
     * SELL → 先平 LONG（若有），再开 SHORT（若无）
     */
    private void executeSignalFutures(Strategy strategy, Signal signal, MarketType marketType) {
        if (signal.getSignalType() == SignalType.BUY) {
            // Step 1: Close SHORT if exists
            Position shortPos = positionManagementService.findOpenPosition(
                    strategy.getId(), strategy.getAccountId(),
                    strategy.getExchange(), strategy.getSymbol(), PositionSide.SHORT);
            if (shortPos != null) {
                log.info("[Futures] Closing SHORT before opening LONG: strategy={} symbol={}",
                        strategy.getName(), strategy.getSymbol());
                submitCloseOrder(shortPos, strategy.getExecutionMode(), marketType);
            }
            // Step 2: Open LONG if not already open
            Position longPos = positionManagementService.findOpenPosition(
                    strategy.getId(), strategy.getAccountId(),
                    strategy.getExchange(), strategy.getSymbol(), PositionSide.LONG);
            if (longPos != null) {
                log.debug("[Futures] Strategy [{}] already has LONG for {} {}, ignoring BUY",
                        strategy.getName(), strategy.getExchange(), strategy.getSymbol());
                return;
            }
            // 日亏损熔断：平仓（Step 1）正常执行，开新仓（Step 2）被暂停拦截
            if (isStrategyTradingPaused(strategy)) {
                log.info("[StopLossPause] Strategy [{}] trading paused until {} UTC, skip LONG open",
                        strategy.getName(), strategy.getTradingPausedUntil());
                return;
            }
            BigDecimal quantity = calculateBuyQuantity(strategy, signal);
            if (quantity == null || quantity.compareTo(BigDecimal.ZERO) <= 0) {
                log.warn("[Futures] Strategy [{}] invalid quantity for LONG open", strategy.getName());
                return;
            }
            submitOpenOrder(strategy, signal, OrderSide.BUY, quantity, false, marketType);

        } else { // SELL signal → open SHORT
            // Step 1: Close LONG if exists
            Position longPos = positionManagementService.findOpenPosition(
                    strategy.getId(), strategy.getAccountId(),
                    strategy.getExchange(), strategy.getSymbol(), PositionSide.LONG);
            if (longPos != null) {
                log.info("[Futures] Closing LONG before opening SHORT: strategy={} symbol={}",
                        strategy.getName(), strategy.getSymbol());
                submitCloseOrder(longPos, strategy.getExecutionMode(), marketType);
            }
            // Step 2: Open SHORT if not already open
            Position shortPos = positionManagementService.findOpenPosition(
                    strategy.getId(), strategy.getAccountId(),
                    strategy.getExchange(), strategy.getSymbol(), PositionSide.SHORT);
            if (shortPos != null) {
                log.debug("[Futures] Strategy [{}] already has SHORT for {} {}, ignoring SELL",
                        strategy.getName(), strategy.getExchange(), strategy.getSymbol());
                return;
            }
            // 日亏损熔断：平仓（Step 1）正常执行，开新仓（Step 2）被暂停拦截
            if (isStrategyTradingPaused(strategy)) {
                log.info("[StopLossPause] Strategy [{}] trading paused until {} UTC, skip SHORT open",
                        strategy.getName(), strategy.getTradingPausedUntil());
                return;
            }
            BigDecimal quantity = calculateBuyQuantity(strategy, signal);
            if (quantity == null || quantity.compareTo(BigDecimal.ZERO) <= 0) {
                log.warn("[Futures] Strategy [{}] invalid quantity for SHORT open", strategy.getName());
                return;
            }
            submitOpenOrder(strategy, signal, OrderSide.SELL, quantity, false, marketType);
        }
    }

    // ─── 平仓入口 ─────────────────────────────────────────────

    /**
     * 统一平仓入口（手动平仓 / 止损止盈自动平仓）
     * PAPER：按当前市价直接更新本地持仓
     * LIVE SPOT：MARKET SELL 单
     * LIVE FUTURES LONG：SELL reduceOnly 单
     * LIVE FUTURES SHORT：BUY reduceOnly 单
     */
    public void executeClose(Position position) {
        // 重新从数据库确认持仓仍为 OPEN，防止 StopLossTakeProfitTask 与 processExitConditions
        // 并发触发时对同一持仓执行双重平仓
        if (!positionManagementService.isStillOpen(position.getId())) {
            log.info("[Close] Position {} is no longer OPEN, skipping duplicate close", position.getId());
            return;
        }
        MarketType marketType = position.getMarketType();
        boolean isFutures = marketType != null && marketType.isFutures();

        if (position.getTradeMode() == ExecutionMode.PAPER) {
            BigDecimal currentPrice = paperTradingService.getCurrentPrice(
                    position.getExchange(), position.getSymbol());
            if (currentPrice == null) {
                log.warn("[Close] No price data for {} {}, cannot close position {}",
                        position.getExchange(), position.getSymbol(), position.getId());
                return;
            }
            positionManagementService.closePosition(position, currentPrice);
            return;
        }

        // 确定平仓方向：平 LONG → SELL；平 SHORT → BUY
        OrderSide closeSide = (isFutures && position.getSide() == PositionSide.SHORT)
                ? OrderSide.BUY : OrderSide.SELL;

        // 现货平仓：对仓位数量做防御性步长对齐，避免因历史数据精度偏差导致 Binance
        // 截断后留下无法再次平仓的微小残余仓位；合约由 Binance 自行对齐，无需处理。
        BigDecimal closeQty = position.getQuantity();
        if (!isFutures) {
            try {
                BigDecimal stepSize = binanceTradeClient.getStepSize(position.getSymbol());
                if (stepSize != null && stepSize.compareTo(BigDecimal.ZERO) > 0) {
                    BigDecimal steps = closeQty.divide(stepSize, 0, RoundingMode.DOWN);
                    closeQty = steps.multiply(stepSize).stripTrailingZeros();
                }
            } catch (Exception e) {
                log.warn("[Close] Failed to align close qty for {}: {}, using original qty={}",
                        position.getSymbol(), e.getMessage(), closeQty);
            }
        }

        Order order = new Order();
        order.setStrategyId(position.getStrategyId());
        order.setAccountId(position.getAccountId());
        order.setExchange(position.getExchange());
        order.setSymbol(position.getSymbol());
        order.setSide(closeSide);
        order.setOrderType(OrderType.MARKET);
        order.setQuantity(closeQty);
        order.setTradeMode(ExecutionMode.LIVE);
        order.setMarketType(marketType);
        order.setReduceOnly(isFutures);
        order.setStatus(OrderStatus.SUBMITTED);
        orderMapper.insert(order);

        log.info("[Close] Submitting live {} order for position {}: {} {} qty={} reduceOnly={}",
                closeSide, position.getId(), position.getExchange(), position.getSymbol(),
                closeQty, order.isReduceOnly());

        doExecute(order, null);
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

    // ─── 内部执行 ─────────────────────────────────────────────

    private void doExecute(Order order, Strategy strategy) {
        try {
            if (order.getTradeMode() == ExecutionMode.PAPER) {
                BigDecimal feeRate = strategy != null ? strategy.getFeeRate() : null;
                paperTradingService.simulateFill(order, feeRate);
            } else {
                if (applySlippageProtection(order)) {
                    // 合约开仓前设置杠杆和保证金模式（幂等，仅对非平仓订单）
                    MarketType mt = order.getMarketType();
                    if (mt != null && mt.isFutures() && !order.isReduceOnly() && strategy != null) {
                        setupFuturesAccount(order, strategy);
                    }
                    executeLive(order);
                }
            }

            orderMapper.updateById(order);

            if (order.getStatus() == OrderStatus.FILLED || order.getStatus() == OrderStatus.SIMULATED) {
                // 步长对齐（仅现货；合约由 BinanceFuturesClient 在下单前已对齐）
                MarketType mt = order.getMarketType();
                if (mt == null || !mt.isFutures()) {
                    BigDecimal stepSize = binanceTradeClient.getStepSize(order.getSymbol());
                    BigDecimal steps = order.getFilledQuantity().divide(stepSize, 0, RoundingMode.DOWN);
                    BigDecimal positionQuantity = steps.multiply(stepSize).stripTrailingZeros();
                    order.setFilledQuantity(positionQuantity);
                }

                // 合约开仓时将策略的杠杆和保证金模式写入 order，供 buildNewPosition 使用
                if (mt != null && mt.isFutures() && !order.isReduceOnly() && strategy != null) {
                    order.setLeverage(strategy.getLeverage());
                    order.setMarginType(strategy.getMarginType());
                }

                positionManagementService.updatePosition(order);

                // 设置止盈止损（开仓订单 + 策略有配置；含现货BUY和合约SHORT开仓）
                if (strategy != null && !order.isReduceOnly()) {
                    setStopLossTakeProfit(order, strategy);
                }
            }

            compositeTradeNotifier.notifyOrderFilled(order);

        } catch (Exception e) {
            order.setStatus(OrderStatus.REJECTED);
            order.setErrorMsg(e.getMessage());
            orderMapper.updateById(order);
            log.error("Trade execution failed for order {}: {}", order.getId(), e.getMessage(), e);
        }
    }

    /**
     * 开仓前设置合约账户参数（杠杆 + 保证金模式）。
     * 利用内存缓存避免对同一账户 + 标的重复调用 Binance API，
     * 仅在首次或配置变更时实际发起请求。
     */
    private void setupFuturesAccount(Order order, Strategy strategy) {
        String[] credentials = accountService.getDecryptedCredentials(order.getAccountId());
        MarketType marketType = order.getMarketType();
        int leverage = strategy.getLeverage() != null ? strategy.getLeverage() : 1;
        MarginType marginType = strategy.getMarginType() != null
                ? strategy.getMarginType() : MarginType.ISOLATED;

        String cacheKey = order.getAccountId() + "_" + order.getSymbol() + "_" + marketType.name();

        try {
            // 杠杆：缓存命中且值未变则跳过
            if (!Integer.valueOf(leverage).equals(leverageCache.get(cacheKey))) {
                binanceFuturesClient.setLeverage(
                        credentials[0], credentials[1], order.getSymbol(), leverage, marketType);
                leverageCache.put(cacheKey, leverage);
                log.info("[Futures] Leverage set: symbol={}, leverage={}", order.getSymbol(), leverage);
            }
            // 保证金模式：缓存命中且值未变则跳过
            if (!marginType.equals(marginTypeCache.get(cacheKey))) {
                binanceFuturesClient.setMarginType(
                        credentials[0], credentials[1], order.getSymbol(), marginType, marketType);
                marginTypeCache.put(cacheKey, marginType);
                log.info("[Futures] MarginType set: symbol={}, marginType={}", order.getSymbol(), marginType);
            }
        } catch (Exception e) {
            // 清除缓存确保下次重试，并向上抛出阻止以错误参数开仓
            leverageCache.remove(cacheKey);
            marginTypeCache.remove(cacheKey);
            log.error("[Futures] Account setup failed for {}: {}", order.getSymbol(), e.getMessage(), e);
            throw new BizException(GlobalError.TRADE_API_ERROR);
        }
    }

    /**
     * 实盘执行（根据 marketType 路由到现货或合约）
     */
    private void executeLive(Order order) {
        String[] credentials = accountService.getDecryptedCredentials(order.getAccountId());
        String apiKey    = credentials[0];
        String apiSecret = credentials[1];

        MarketType marketType = order.getMarketType();
        if (marketType != null && marketType.isFutures()) {
            executeLiveFutures(order, apiKey, apiSecret, marketType);
        } else {
            executeLiveSpot(order, apiKey, apiSecret);
        }
    }

    /**
     * 现货实盘执行
     */
    private void executeLiveSpot(Order order, String apiKey, String apiSecret) {
        JSONObject result = binanceTradeClient.placeOrder(
                apiKey, apiSecret,
                order.getSymbol(),
                order.getSide().name(),
                order.getOrderType().name(),
                order.getQuantity(),
                order.getPrice()
        );

        order.setExchangeOrderId(result.getString("orderId"));
        String status = result.getString("status");

        if ("FILLED".equals(status)) {
            order.setStatus(OrderStatus.FILLED);
            BigDecimal executedQty  = result.getBigDecimal("executedQty");
            BigDecimal cummQuoteQty = result.getBigDecimal("cummulativeQuoteQty");

            if (executedQty != null && executedQty.compareTo(BigDecimal.ZERO) > 0) {
                order.setFilledPrice(cummQuoteQty.divide(executedQty, 10, RoundingMode.HALF_UP));
            }

            BigDecimal totalFee = BigDecimal.ZERO;
            boolean feeInBaseAsset = false;
            JSONArray fills = result.getJSONArray("fills");
            if (fills != null && !fills.isEmpty()) {
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

            if (order.getSide() == OrderSide.BUY && feeInBaseAsset) {
                order.setFilledQuantity(executedQty.subtract(totalFee));
                log.info("[Spot Live] BUY fee deducted: executedQty={}, fee={}, actualQty={}",
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
     * 合约实盘执行（USDM / COINM）
     */
    private void executeLiveFutures(Order order, String apiKey, String apiSecret, MarketType marketType) {
        log.info("[Futures] Placing order: symbol={}, side={}, type={}, qty={}, price={}, reduceOnly={}, market={}",
                order.getSymbol(), order.getSide(), order.getOrderType(),
                order.getQuantity(), order.getPrice(), order.isReduceOnly(), marketType);
        JSONObject result = binanceFuturesClient.placeOrder(
                apiKey, apiSecret,
                order.getSymbol(),
                order.getSide().name(),
                order.getOrderType().name(),
                order.getQuantity(),
                order.getPrice(),
                order.isReduceOnly(),
                marketType
        );

        order.setExchangeOrderId(result.getString("orderId"));
        String status = result.getString("status");

        if ("FILLED".equals(status)) {
            order.setStatus(OrderStatus.FILLED);
            BigDecimal executedQty = result.getBigDecimal("executedQty");

            // 成交均价：优先 avgPrice，否则从 cumQuote 计算
            BigDecimal avgPrice = result.getBigDecimal("avgPrice");
            if ((avgPrice == null || avgPrice.compareTo(BigDecimal.ZERO) == 0)
                    && executedQty != null && executedQty.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal cumQuote = result.getBigDecimal("cumQuote");
                if (cumQuote != null && cumQuote.compareTo(BigDecimal.ZERO) > 0) {
                    avgPrice = cumQuote.divide(executedQty, 10, RoundingMode.HALF_UP);
                }
            }

            order.setFilledPrice(avgPrice);
            order.setFilledQuantity(executedQty);
            // 合约手续费单独扣除（不从持仓数量减）
            order.setFee(BigDecimal.ZERO);

            log.info("[Futures Live] {} {} FILLED: qty={}, avgPrice={}, reduceOnly={}",
                    order.getSide(), order.getSymbol(), executedQty, avgPrice, order.isReduceOnly());
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

    // ─── 订单构建辅助方法 ────────────────────────────────────

    /**
     * 提交平仓订单（合约专用，始终 AUTO 执行以避免延迟造成风险）
     */
    private void submitCloseOrder(Position position, ExecutionMode executionMode, MarketType marketType) {
        OrderSide closeSide = position.getSide() == PositionSide.LONG ? OrderSide.SELL : OrderSide.BUY;

        Order order = new Order();
        order.setStrategyId(position.getStrategyId());
        order.setAccountId(position.getAccountId());
        order.setExchange(position.getExchange());
        order.setSymbol(position.getSymbol());
        order.setSide(closeSide);
        order.setOrderType(OrderType.MARKET);
        order.setQuantity(position.getQuantity());
        order.setTradeMode(executionMode);
        order.setMarketType(marketType);
        order.setReduceOnly(true);
        order.setStatus(OrderStatus.SUBMITTED);
        orderMapper.insert(order);

        log.info("[Futures] Close order: {} {} qty={} closeSide={}",
                position.getExchange(), position.getSymbol(), position.getQuantity(), closeSide);
        doExecute(order, null);
    }

    /**
     * 提交开仓订单（现货 / 合约共用，支持 MANUAL 模式）
     */
    private void submitOpenOrder(Strategy strategy, Signal signal, OrderSide side,
                                  BigDecimal quantity, boolean reduceOnly, MarketType marketType) {
        Order order = new Order();
        order.setStrategyId(strategy.getId());
        order.setAccountId(strategy.getAccountId());
        order.setSignalId(signal != null ? signal.getId() : null);
        order.setExchange(strategy.getExchange());
        order.setSymbol(strategy.getSymbol());
        order.setSide(side);
        order.setOrderType(OrderType.MARKET);
        order.setQuantity(quantity);
        order.setPrice(signal != null ? signal.getPrice() : null);
        order.setTradeMode(strategy.getExecutionMode());
        order.setMarketType(marketType);
        order.setReduceOnly(reduceOnly);

        TradeMode tradeMode = strategy.getTradeMode() != null ? strategy.getTradeMode() : TradeMode.AUTO;
        if (tradeMode == TradeMode.MANUAL) {
            order.setStatus(OrderStatus.PENDING);
            orderMapper.insert(order);
            compositeTradeNotifier.notifyOrderCreated(order, strategy);
            log.info("Pending order created for strategy [{}]: {} {} qty={}",
                    strategy.getName(), side, strategy.getSymbol(), quantity);
        } else {
            order.setStatus(OrderStatus.SUBMITTED);
            orderMapper.insert(order);
            compositeTradeNotifier.notifyOrderCreated(order, strategy);
            doExecute(order, strategy);
        }
    }

    // ─── 数量计算 ─────────────────────────────────────────────

    private BigDecimal calculateBuyQuantity(Strategy strategy, Signal signal) {
        if (strategy.getPositionSizing() != PositionSizing.PERCENT) {
            return strategy.getTradeQuantity();
        }

        BigDecimal currentPrice = null;
        if (strategy.getExecutionMode() == ExecutionMode.LIVE
                && signal != null && signal.getPrice() != null
                && signal.getPrice().compareTo(BigDecimal.ZERO) > 0) {
            currentPrice = signal.getPrice();
        }
        if (currentPrice == null) {
            currentPrice = paperTradingService.getCurrentPrice(strategy.getExchange(), strategy.getSymbol());
        }
        if (currentPrice == null || currentPrice.compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("[PositionSizing] No price data for {} {}, falling back to tradeQuantity",
                    strategy.getExchange(), strategy.getSymbol());
            return strategy.getTradeQuantity();
        }

        BigDecimal positionRatio = strategy.getPositionRatio() != null
                ? strategy.getPositionRatio() : BigDecimal.ONE;

        BigDecimal availableCapital;
        if (strategy.getExecutionMode() == ExecutionMode.LIVE) {
            availableCapital = getAvailableCapitalLive(strategy);
        } else {
            availableCapital = getAvailableCapitalPaper(strategy);
        }

        if (availableCapital == null || availableCapital.compareTo(BigDecimal.ZERO) <= 0) {
            if (strategy.getExecutionMode() == ExecutionMode.LIVE) {
                log.warn("[PositionSizing] Failed to query live balance for strategy [{}], skipping order",
                        strategy.getName());
                return null;
            }
            log.warn("[PositionSizing] No available capital for strategy [{}], falling back to tradeQuantity",
                    strategy.getName());
            return strategy.getTradeQuantity();
        }

        BigDecimal tradeAmount = availableCapital.multiply(positionRatio);
        BigDecimal feeRate = strategy.getFeeRate() != null ? strategy.getFeeRate() : BigDecimal.ZERO;
        BigDecimal netAmount = tradeAmount.multiply(BigDecimal.ONE.subtract(feeRate));

        if (strategy.getExecutionMode() == ExecutionMode.LIVE) {
            netAmount = netAmount.multiply(new BigDecimal("0.98"));
        }

        // 合约杠杆：名义价值 = 保证金 × 杠杆，实际持仓数量按名义价值计算
        int lev = (strategy.getLeverage() != null && strategy.getLeverage() > 1) ? strategy.getLeverage() : 1;
        if (lev > 1) {
            netAmount = netAmount.multiply(BigDecimal.valueOf(lev));
        }

        BigDecimal quantity = netAmount.divide(currentPrice, 8, RoundingMode.DOWN);

        log.info("[PositionSizing] PERCENT mode: capital={}, ratio={}, tradeAmount={}, leverage={}, price={}, qty={}",
                availableCapital, positionRatio, tradeAmount, lev, currentPrice, quantity);
        return quantity;
    }

    private BigDecimal getAvailableCapitalLive(Strategy strategy) {
        if (strategy.getAccountId() == null) return null;
        String quoteAsset = extractQuoteAsset(strategy.getSymbol());
        return accountService.getAvailableBalance(strategy.getAccountId(), quoteAsset);
    }

    private BigDecimal getAvailableCapitalPaper(Strategy strategy) {
        BigDecimal initialCapital = strategy.getInitialCapital() != null
                ? strategy.getInitialCapital() : new BigDecimal("10000");
        BigDecimal totalRealizedPnl = positionManagementService.getTotalRealizedPnl(
                strategy.getId(), strategy.getAccountId());
        // getOccupiedCapital 返回名义价值（entryPrice × quantity），合约模式需除以杠杆还原为保证金占用
        BigDecimal occupiedCapital = positionManagementService.getOccupiedCapital(
                strategy.getId(), strategy.getAccountId());
        int lev = (strategy.getLeverage() != null && strategy.getLeverage() > 1) ? strategy.getLeverage() : 1;
        if (lev > 1) {
            occupiedCapital = occupiedCapital.divide(BigDecimal.valueOf(lev), 8, RoundingMode.HALF_UP);
        }
        BigDecimal available = initialCapital.add(totalRealizedPnl).subtract(occupiedCapital);
        log.debug("[PositionSizing] Paper capital: initial={}, pnl={}, occupied(margin)={}, leverage={}, available={}",
                initialCapital, totalRealizedPnl, occupiedCapital, lev, available);
        return available;
    }

    // ─── 滑点保护 ─────────────────────────────────────────────

    private boolean applySlippageProtection(Order order) {
        TradingProperties.Slippage config = tradingProperties.getSlippage();
        if (config == null || !config.isEnabled()) {
            return true;
        }

        // 平仓订单不做滑点转限价：reduceOnly 合约平仓，或无信号价格的平仓单
        // 始终保持 MARKET 单，确保立即成交，避免限价单未成交导致仓位无法平掉
        if (order.isReduceOnly()
                || order.getPrice() == null
                || order.getPrice().compareTo(BigDecimal.ZERO) <= 0) {
            log.debug("[Slippage] Close order detected, skipping LIMIT conversion for {} {}",
                    order.getSide(), order.getSymbol());
            return true;
        }

        BigDecimal currentPrice = paperTradingService.getCurrentPrice(
                order.getExchange(), order.getSymbol());
        if (currentPrice == null) {
            log.warn("[Slippage] No price data for {} {}, falling back to MARKET order",
                    order.getExchange(), order.getSymbol());
            return true;
        }

        BigDecimal signalPrice = order.getPrice();
        if (signalPrice != null && signalPrice.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal deviation = currentPrice.subtract(signalPrice).abs()
                    .divide(signalPrice, 6, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100));

            if (deviation.compareTo(config.getMaxSlippagePct()) > 0) {
                log.warn("[Slippage] Price deviation {}% exceeds max {}%, rejecting order",
                        deviation, config.getMaxSlippagePct());
                order.setStatus(OrderStatus.REJECTED);
                order.setErrorMsg(String.format("价格偏差 %s%% 超过阈值 %s%% (信号价: %s, 当前价: %s)",
                        deviation.setScale(2, RoundingMode.HALF_UP),
                        config.getMaxSlippagePct(), signalPrice, currentPrice));
                return false;
            }
        }

        BigDecimal limitPricePct = config.getLimitPricePct()
                .divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP);
        BigDecimal limitPrice;
        if (order.getSide() == OrderSide.BUY) {
            limitPrice = currentPrice.multiply(BigDecimal.ONE.add(limitPricePct));
        } else {
            limitPrice = currentPrice.multiply(BigDecimal.ONE.subtract(limitPricePct));
        }

        order.setOrderType(OrderType.LIMIT);
        order.setPrice(limitPrice.setScale(getPriceScale(currentPrice), RoundingMode.HALF_UP));

        log.info("[Slippage] Converted to LIMIT order: {} {} price={} (current={}, signal={})",
                order.getSide(), order.getSymbol(), order.getPrice(), currentPrice, signalPrice);
        return true;
    }

    private int getPriceScale(BigDecimal price) {
        if (price.compareTo(BigDecimal.valueOf(10)) < 0) return 4;
        return 2;
    }

    // ─── 止盈止损 ─────────────────────────────────────────────

    /**
     * 设置持仓的止损 / 止盈价格。
     * <p>
     * 优先级：移动ATR止损（四参数） > 固定ATR止损 > 固定百分比止损<br>
     * 止盈优先级：ATR倍数止盈 > 固定百分比止盈<br>
     * 同时支持 LONG 和 SHORT 持仓方向。
     * </p>
     */
    public void setStopLossTakeProfit(Order order, Strategy strategy) {
        boolean hasTrailingStop = strategy.getInitialStopMultiplier() != null
                && strategy.getInitialStopMultiplier().compareTo(BigDecimal.ZERO) > 0;
        boolean hasAtrStop   = strategy.getAtrStopMultiplier() != null
                && strategy.getAtrStopMultiplier().compareTo(BigDecimal.ZERO) > 0;
        boolean hasPctStop   = strategy.getStopLossPct() != null;
        boolean hasAtrTp     = strategy.getAtrTakeProfitMultiplier() != null
                && strategy.getAtrTakeProfitMultiplier().compareTo(BigDecimal.ZERO) > 0;
        boolean hasPctTp     = strategy.getTakeProfitPct() != null;

        if (!hasTrailingStop && !hasAtrStop && !hasPctStop && !hasAtrTp && !hasPctTp) {
            return;
        }

        // 合约开仓后，精准按方向查询持仓，避免同一 symbol 下 LONG/SHORT 并存时取到错误方向
        MarketType mt = order.getMarketType();
        boolean isFuturesOrder = mt != null && mt.isFutures();
        Position position;
        if (isFuturesOrder) {
            PositionSide targetSide = order.getSide() == OrderSide.BUY ? PositionSide.LONG : PositionSide.SHORT;
            position = positionManagementService.findOpenPosition(
                    order.getStrategyId(), order.getAccountId(),
                    order.getExchange(), order.getSymbol(), targetSide);
        } else {
            position = positionManagementService.findOpenPosition(
                    order.getStrategyId(), order.getAccountId(), order.getExchange(), order.getSymbol());
        }
        if (position == null) return;

        BigDecimal entryPrice = position.getEntryPrice();
        boolean isShort = position.getSide() == PositionSide.SHORT;

        // ── 移动ATR止损：四参数联动，优先级最高 ──────────────
        KLineInterval effectiveAtrInterval = strategy.getAtrInterval() != null
                ? strategy.getAtrInterval() : strategy.getInterval();
        if (hasTrailingStop) {
            BigDecimal atrValue = computeAtr(
                    strategy.getExchange(), strategy.getSymbol(), effectiveAtrInterval, 14);
            if (atrValue != null && atrValue.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal atrOffset = atrValue.multiply(strategy.getInitialStopMultiplier());
                BigDecimal stopLoss = isShort
                        ? entryPrice.add(atrOffset)
                        : entryPrice.subtract(atrOffset);
                position.setStopLoss(stopLoss.setScale(8, RoundingMode.HALF_UP));
                position.setStopLossStage(StopLossStage.INITIAL);
                // 初始化极值追踪字段
                position.setHighestPrice(entryPrice);
                position.setLowestPrice(entryPrice);
                log.info("[Trailing SL] strategy={} side={} entryPrice={} atr={} initialStop={} stopLoss={}",
                        strategy.getName(), position.getSide(), entryPrice,
                        atrValue, strategy.getInitialStopMultiplier(), position.getStopLoss());
            } else {
                log.warn("[Trailing SL] Cannot compute ATR for {} {}", strategy.getExchange(), strategy.getSymbol());
            }
            // 移动止损模式下止盈仍走正常逻辑
        } else if (hasAtrStop || hasAtrTp) {
            // ── 固定ATR止损/止盈 ──────────────────────────────
            BigDecimal atrValue = computeAtr(
                    strategy.getExchange(), strategy.getSymbol(), effectiveAtrInterval, 14);

            if (hasAtrStop && atrValue != null && atrValue.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal atrOffset = atrValue.multiply(strategy.getAtrStopMultiplier());
                BigDecimal stopLoss = isShort
                        ? entryPrice.add(atrOffset)
                        : entryPrice.subtract(atrOffset);
                position.setStopLoss(stopLoss.setScale(8, RoundingMode.HALF_UP));
                log.info("[ATR Stop] strategy={} side={} entryPrice={} atr={} multiplier={} stopLoss={}",
                        strategy.getName(), position.getSide(), entryPrice,
                        atrValue, strategy.getAtrStopMultiplier(), position.getStopLoss());
            }

            if (hasAtrTp && atrValue != null && atrValue.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal atrOffset = atrValue.multiply(strategy.getAtrTakeProfitMultiplier());
                BigDecimal takeProfit = isShort
                        ? entryPrice.subtract(atrOffset)
                        : entryPrice.add(atrOffset);
                position.setTakeProfit(takeProfit.setScale(8, RoundingMode.HALF_UP));
                log.info("[ATR TP] strategy={} side={} entryPrice={} atr={} multiplier={} takeProfit={}",
                        strategy.getName(), position.getSide(), entryPrice,
                        atrValue, strategy.getAtrTakeProfitMultiplier(), position.getTakeProfit());
            }
        }

        // ── 非ATR路径：固定百分比止损 ────────────────────────
        if (!hasTrailingStop && !hasAtrStop && hasPctStop) {
            BigDecimal pct = strategy.getStopLossPct()
                    .divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP);
            BigDecimal stopLoss = isShort
                    ? entryPrice.multiply(BigDecimal.ONE.add(pct))
                    : entryPrice.multiply(BigDecimal.ONE.subtract(pct));
            position.setStopLoss(stopLoss.setScale(8, RoundingMode.HALF_UP));
            log.info("[Fixed SL] strategy={} side={} entryPrice={} pct={}% stopLoss={}",
                    strategy.getName(), position.getSide(), entryPrice,
                    strategy.getStopLossPct(), position.getStopLoss());
        }

        // ── 非ATR路径：固定百分比止盈 ────────────────────────
        if (!hasAtrTp && hasPctTp) {
            BigDecimal pct = strategy.getTakeProfitPct()
                    .divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP);
            BigDecimal takeProfit = isShort
                    ? entryPrice.multiply(BigDecimal.ONE.subtract(pct))
                    : entryPrice.multiply(BigDecimal.ONE.add(pct));
            position.setTakeProfit(takeProfit.setScale(8, RoundingMode.HALF_UP));
            log.info("[Fixed TP] strategy={} side={} entryPrice={} pct={}% takeProfit={}",
                    strategy.getName(), position.getSide(), entryPrice,
                    strategy.getTakeProfitPct(), position.getTakeProfit());
        }

        // ── 峰值回撤止损：初始化极值追踪（与移动ATR止损独立并存）────────
        // 若 ATR 移动止损已初始化 highestPrice/lowestPrice 则不重复覆盖，否则在此初始化
        if (strategy.getTrailingDropPct() != null
                && strategy.getTrailingDropPct().compareTo(BigDecimal.ZERO) > 0) {
            if (position.getHighestPrice() == null) position.setHighestPrice(entryPrice);
            if (position.getLowestPrice()  == null) position.setLowestPrice(entryPrice);
        }

        positionManagementService.updateStopLossTakeProfit(position);
    }

    /**
     * K线收盘后更新该策略所有OPEN持仓的移动ATR止损阶段。
     * <p>
     * 由 TradeExecutionListenerImpl.onKLineClose() 调用，
     * 按 INITIAL → BREAKEVEN → TRAILING 状态机推进止损价。
     * </p>
     *
     * @param strategy 策略（含四参数）
     * @param atrValue 最新ATR值（由 StrategyEngineService 计算后传入，避免重复查询）
     * @param klines   主周期K线（升序），取末尾收盘价作为当前价格
     */
    public void updateTrailingStops(Strategy strategy, BigDecimal atrValue, List<KLine> klines) {
        if (atrValue == null || atrValue.compareTo(BigDecimal.ZERO) <= 0) return;
        if (klines == null || klines.isEmpty()) return;

        BigDecimal currentPrice = klines.get(klines.size() - 1).getClose();
        if (currentPrice == null) return;

        List<Position> openPositions = positionManagementService.findOpenPositionsByStrategy(strategy.getId());
        if (openPositions.isEmpty()) return;

        for (Position position : openPositions) {
            try {
                boolean isShort = position.getSide() == PositionSide.SHORT;
                StopLossStage stage = position.getStopLossStage();
                // stage == null 表示该持仓不是追踪止损模式（非本功能管辖），直接跳过
                if (stage == null) continue;

                switch (stage) {
                    case INITIAL -> evaluateBreakeven(position, strategy, currentPrice, atrValue, isShort);
                    case BREAKEVEN -> evaluateTrailingActivation(position, strategy, currentPrice, atrValue, isShort);
                    case TRAILING -> updateTrailingStopPrice(position, strategy, currentPrice, atrValue, isShort);
                }
            } catch (Exception e) {
                log.error("[Trailing SL] Error updating position {}: {}", position.getId(), e.getMessage(), e);
            }
        }
    }

    // ─── 出场条件处理 ─────────────────────────────────────────────────

    /**
     * 处理出场条件：时间止损 + 指标出场。
     * <p>
     * 每根K线收盘后由 {@link com.vertex.service.order.service.TradeExecutionListenerImpl#onExitConditionCheck} 触发。
     * <ul>
     *   <li>更新各持仓的 openBarCount（开仓后经历的K线根数）</li>
     *   <li>检查 maxHoldingBars 时间止损，超过则强制平仓</li>
     *   <li>根据 exitSignalType 判断方向：多头+SELL 或 空头+BUY 则平仓</li>
     * </ul>
     * </p>
     *
     * @param strategy        策略配置（含 maxHoldingBars、minSignalStrength）
     * @param exitSignalType  出场指标评估结果（NEUTRAL=无信号；SELL=多头出场；BUY=空头出场）
     * @param signalStrength  出场信号强度（0-100）
     */
    public void processExitConditions(Strategy strategy, SignalType exitSignalType, int signalStrength) {
        List<Position> openPositions = positionManagementService.findOpenPositionsByStrategy(strategy.getId());
        if (openPositions.isEmpty()) return;

        int minStrength = (strategy.getMinSignalStrength() != null && strategy.getMinSignalStrength() > 0)
                ? strategy.getMinSignalStrength() : 0;

        for (Position pos : openPositions) {
            try {
                // 1. 更新持仓K线计数
                int barCount = (pos.getOpenBarCount() != null ? pos.getOpenBarCount() : 0) + 1;
                pos.setOpenBarCount(barCount);
                positionManagementService.updateStopLossTakeProfit(pos);

                // 2. 时间止损：超过最大持仓K线数强制平仓
                if (strategy.getMaxHoldingBars() != null && barCount >= strategy.getMaxHoldingBars()) {
                    log.info("[出场-时间止损] positionId={} side={} barCount={} maxBars={}",
                            pos.getId(), pos.getSide(), barCount, strategy.getMaxHoldingBars());
                    executeClose(pos);
                    continue;
                }

                // 3. 指标出场：NEUTRAL 信号时跳过；方向需与持仓方向匹配
                if (exitSignalType == SignalType.NEUTRAL) continue;
                boolean isLong = pos.getSide() == PositionSide.LONG;
                boolean shouldExit = (isLong && exitSignalType == SignalType.SELL)
                        || (!isLong && exitSignalType == SignalType.BUY);

                if (shouldExit && signalStrength >= minStrength) {
                    log.info("[出场-指标] positionId={} side={} signal={} strength={}",
                            pos.getId(), pos.getSide(), exitSignalType, signalStrength);
                    executeClose(pos);
                }
            } catch (Exception e) {
                log.error("[Exit] Error processing exit for position {}: {}", pos.getId(), e.getMessage(), e);
            }
        }
    }

    /** INITIAL → BREAKEVEN：价格突破 entry + breakevenActivationMultiplier × ATR */
    private void evaluateBreakeven(Position position, Strategy strategy,
                                   BigDecimal currentPrice, BigDecimal atrValue, boolean isShort) {
        if (strategy.getBreakevenActivationMultiplier() == null) return;
        BigDecimal entryPrice = position.getEntryPrice();
        BigDecimal threshold = atrValue.multiply(strategy.getBreakevenActivationMultiplier());

        boolean activated = isShort
                ? currentPrice.compareTo(entryPrice.subtract(threshold)) <= 0
                : currentPrice.compareTo(entryPrice.add(threshold)) >= 0;

        if (activated) {
            position.setStopLoss(entryPrice.setScale(8, RoundingMode.HALF_UP));
            position.setStopLossStage(StopLossStage.BREAKEVEN);
            positionManagementService.updateStopLossTakeProfit(position);
            log.info("[Trailing SL] BREAKEVEN activated: position={} side={} stopLoss=entryPrice={}",
                    position.getId(), position.getSide(), entryPrice);
        }
    }

    /** BREAKEVEN → TRAILING：价格突破 entry + trailingActivationMultiplier × ATR */
    private void evaluateTrailingActivation(Position position, Strategy strategy,
                                            BigDecimal currentPrice, BigDecimal atrValue, boolean isShort) {
        if (strategy.getTrailingActivationMultiplier() == null) return;
        BigDecimal entryPrice = position.getEntryPrice();
        BigDecimal threshold = atrValue.multiply(strategy.getTrailingActivationMultiplier());

        boolean activated = isShort
                ? currentPrice.compareTo(entryPrice.subtract(threshold)) <= 0
                : currentPrice.compareTo(entryPrice.add(threshold)) >= 0;

        if (activated) {
            // 初始化极值追踪字段并立即计算追踪止损。
            // 若峰值回撤止损已记录了更优的历史极值（LONG:更高；SHORT:更低），保留其值，
            // 避免覆盖后使峰值回撤止损阈值变得宽松。
            if (isShort) {
                if (position.getLowestPrice() == null || currentPrice.compareTo(position.getLowestPrice()) < 0) {
                    position.setLowestPrice(currentPrice);
                }
            } else {
                if (position.getHighestPrice() == null || currentPrice.compareTo(position.getHighestPrice()) > 0) {
                    position.setHighestPrice(currentPrice);
                }
            }
            position.setStopLossStage(StopLossStage.TRAILING);
            updateTrailingStopPrice(position, strategy, currentPrice, atrValue, isShort);
            log.info("[Trailing SL] TRAILING activated: position={} side={} currentPrice={}",
                    position.getId(), position.getSide(), currentPrice);
        }
    }

    /** TRAILING：更新极值价格，止损只朝有利方向移动（LONG只升，SHORT只降） */
    private void updateTrailingStopPrice(Position position, Strategy strategy,
                                         BigDecimal currentPrice, BigDecimal atrValue, boolean isShort) {
        if (strategy.getTrailingDistanceMultiplier() == null) return;
        BigDecimal trailingOffset = atrValue.multiply(strategy.getTrailingDistanceMultiplier());
        boolean updated = false;

        if (isShort) {
            BigDecimal lowestPrice = position.getLowestPrice() != null ? position.getLowestPrice() : currentPrice;
            if (currentPrice.compareTo(lowestPrice) < 0) {
                position.setLowestPrice(currentPrice);
                lowestPrice = currentPrice;
                updated = true;
            }
            // SHORT 止损 = 最低价 + 追踪距离（止损只下移）
            BigDecimal newStop = lowestPrice.add(trailingOffset).setScale(8, RoundingMode.HALF_UP);
            if (position.getStopLoss() == null || newStop.compareTo(position.getStopLoss()) < 0) {
                position.setStopLoss(newStop);
                updated = true;
            }
        } else {
            BigDecimal highestPrice = position.getHighestPrice() != null ? position.getHighestPrice() : currentPrice;
            if (currentPrice.compareTo(highestPrice) > 0) {
                position.setHighestPrice(currentPrice);
                highestPrice = currentPrice;
                updated = true;
            }
            // LONG 止损 = 最高价 - 追踪距离（止损只上移）
            BigDecimal newStop = highestPrice.subtract(trailingOffset).setScale(8, RoundingMode.HALF_UP);
            if (position.getStopLoss() == null || newStop.compareTo(position.getStopLoss()) > 0) {
                position.setStopLoss(newStop);
                updated = true;
            }
        }

        if (updated) {
            positionManagementService.updateStopLossTakeProfit(position);
            log.debug("[Trailing SL] Stop updated: position={} side={} newStop={}",
                    position.getId(), position.getSide(), position.getStopLoss());
        }
    }

    /**
     * 使用 Wilder 平滑法（与 AtrIndicator 算法一致）计算 ATR。
     *
     * @param exchange 交易所
     * @param symbol   交易对
     * @param interval K 线周期
     * @param period   ATR 周期（通常为 14）
     * @return ATR 绝对值，数据不足时返回 null
     */
    private BigDecimal computeAtr(String exchange, String symbol, KLineInterval interval, int period) {
        try {
            // 多取 3 倍 period：前 period 根用作 Wilder 平滑种子，再 period 根用于充分预热，
            // 最后 1 根用于计算 True Range（需要 prevClose）。
            List<KLine> klines = klineStore.query(
                    exchange, symbol, interval, null, null, period * 3 + 1, true);
            if (klines == null || klines.size() < 2) {
                log.warn("[ATR] Insufficient kline data for {} {} {}: got {}",
                        exchange, symbol, interval, klines == null ? 0 : klines.size());
                return null;
            }

            // 计算 True Range 序列
            double[] trueRanges = new double[klines.size() - 1];
            for (int i = 1; i < klines.size(); i++) {
                KLine cur  = klines.get(i);
                KLine prev = klines.get(i - 1);
                double highLow   = cur.getHigh().doubleValue()  - cur.getLow().doubleValue();
                double highClose = Math.abs(cur.getHigh().doubleValue() - prev.getClose().doubleValue());
                double lowClose  = Math.abs(cur.getLow().doubleValue()  - prev.getClose().doubleValue());
                trueRanges[i - 1] = Math.max(highLow, Math.max(highClose, lowClose));
            }

            // Wilder 平滑（首值取简单均值）
            int calcPeriod = Math.min(period, trueRanges.length);
            double atr = 0;
            for (int i = 0; i < calcPeriod; i++) atr += trueRanges[i];
            atr /= calcPeriod;
            for (int i = calcPeriod; i < trueRanges.length; i++) {
                atr = (atr * (period - 1) + trueRanges[i]) / period;
            }

            return BigDecimal.valueOf(atr);
        } catch (Exception e) {
            log.error("[ATR] Failed to compute ATR for {} {} {}: {}", exchange, symbol, interval, e.getMessage());
            return null;
        }
    }

    // ─── 工具方法 ─────────────────────────────────────────────

    // ─── 日亏损熔断 ───────────────────────────────────────────

    /**
     * 判断策略当前是否处于日亏损熔断暂停期。
     * <p>
     * 每次均从 DB 重新读取最新状态，避免信号处理与 StopLossTakeProfitTask
     * 并发写入之间的竞态——两者可能在同一根 K 线内分别读写 tradingPausedUntil。
     * tradingPausedUntil 在 DB 中持久化（ALWAYS 策略），重启后仍有效。
     * </p>
     */
    private boolean isStrategyTradingPaused(Strategy strategy) {
        try {
            Strategy fresh = strategyRefMapper.selectById(strategy.getId());
            if (fresh == null || fresh.getTradingPausedUntil() == null) return false;
            return LocalDateTime.now(ZoneOffset.UTC).isBefore(fresh.getTradingPausedUntil());
        } catch (Exception e) {
            log.warn("[StopLossPause] Failed to reload strategy {} for pause check, using cached value: {}",
                    strategy.getId(), e.getMessage());
            // 降级：使用传入对象的缓存值，保证不因 DB 异常阻塞交易
            if (strategy.getTradingPausedUntil() == null) return false;
            return LocalDateTime.now(ZoneOffset.UTC).isBefore(strategy.getTradingPausedUntil());
        }
    }

    /**
     * 止损专用平仓入口：执行平仓后，若策略启用了止损熔断开关且仓位实际亏损，则触发 24h 暂停。
     * <p>
     * 仅应由止损机制调用（固定止损、ATR移动止损、峰值回撤止损），不应由信号出场、止盈、时间止损调用。
     * 移动止损在盈利区间触发（如追踪锁利）不会激活熔断。
     * </p>
     */
    public void executeStopLossClose(Position position) {
        executeClose(position);
        // 仅当策略开启止损熔断 且 仓位实际亏损时触发暂停
        if (isPositionAtLoss(position)) {
            checkAndApplyStopLossPause(position);
        }
    }

    /**
     * 判断仓位是否以亏损状态平仓。
     * <ul>
     *   <li>PAPER 模式：{@code closePosition()} 执行后 realizedPnl 已更新，直接使用。</li>
     *   <li>LIVE 模式：{@code StopLossTakeProfitTask} 在调用前已更新 unrealizedPnl，以此作为代理。</li>
     *   <li>兜底：比较止损价与入场价方向判断。</li>
     * </ul>
     */
    private boolean isPositionAtLoss(Position position) {
        // PAPER：closePosition() 已将 realizedPnl 写入 position 对象
        if (position.getTradeMode() == ExecutionMode.PAPER) {
            return position.getRealizedPnl() != null
                    && position.getRealizedPnl().compareTo(BigDecimal.ZERO) < 0;
        }
        // LIVE：StopLossTakeProfitTask 在调用前刷新了 unrealizedPnl，以此估算方向
        if (position.getUnrealizedPnl() != null) {
            return position.getUnrealizedPnl().compareTo(BigDecimal.ZERO) < 0;
        }
        // 兜底：止损价与入场价的方向关系
        // LONG  SL < entryPrice → 亏损区; LONG  SL > entryPrice → 移动止损已锁利
        // SHORT SL > entryPrice → 亏损区; SHORT SL < entryPrice → 移动止损已锁利
        if (position.getEntryPrice() != null && position.getStopLoss() != null) {
            boolean isShort = position.getSide() == PositionSide.SHORT;
            return isShort
                    ? position.getStopLoss().compareTo(position.getEntryPrice()) > 0
                    : position.getStopLoss().compareTo(position.getEntryPrice()) < 0;
        }
        return false; // 无法判断时保守处理：不触发熔断
    }

    /**
     * 止损平仓后触发熔断暂停。
     * <p>
     * 触发条件：策略启用了 pauseOnStopLoss 开关（=1）
     * 触发动作：将 tradingPausedUntil 设置为 now+24h 并写入 DB，重启后仍有效。
     * </p>
     */
    private void checkAndApplyStopLossPause(Position position) {
        if (position.getStrategyId() == null) return;
        try {
            Strategy strategy = strategyRefMapper.selectById(position.getStrategyId());
            if (strategy == null
                    || strategy.getPauseOnStopLoss() == null
                    || strategy.getPauseOnStopLoss() != 1) return;

            // 已处于有效暂停期内则跳过（不重复写库）
            if (strategy.getTradingPausedUntil() != null
                    && LocalDateTime.now(ZoneOffset.UTC).isBefore(strategy.getTradingPausedUntil())) {
                return;
            }
            LocalDateTime pauseUntil = LocalDateTime.now(ZoneOffset.UTC).plusHours(24);
            strategy.setTradingPausedUntil(pauseUntil);
            strategyRefMapper.updateById(strategy);
            log.warn("[StopLossPause] 熔断触发 strategy=[{}] 止损亏损，暂停开仓至 {} UTC",
                    strategy.getName(), pauseUntil);
        } catch (Exception e) {
            log.error("[StopLossPause] Error applying stop-loss pause for position {}: {}",
                    position.getId(), e.getMessage(), e);
        }
    }

    private MarketType getAccountMarketType(Long accountId) {
        if (accountId == null) return null;
        try {
            ExchangeAccount account = accountService.getAccountEntity(accountId);
            return account.getMarketType();
        } catch (Exception e) {
            log.warn("Failed to get account market type for accountId={}: {}", accountId, e.getMessage());
            return null;
        }
    }

    private String extractQuoteAsset(String symbol) {
        if (symbol == null) return "USDT";
        int idx = symbol.indexOf('-');
        if (idx > 0) return symbol.substring(idx + 1).toUpperCase();
        String upper = symbol.toUpperCase();
        for (String quote : new String[]{"USDT", "BUSD", "USDC", "BTC", "ETH", "BNB"}) {
            if (upper.endsWith(quote)) return quote;
        }
        return "USDT";
    }

    private String extractBaseAsset(String symbol) {
        if (symbol == null) return null;
        int idx = symbol.indexOf('-');
        if (idx > 0) return symbol.substring(0, idx).toUpperCase();
        String upper = symbol.toUpperCase();
        for (String quote : new String[]{"USDT", "BUSD", "USDC", "BTC", "ETH", "BNB"}) {
            if (upper.endsWith(quote) && upper.length() > quote.length()) {
                return upper.substring(0, upper.length() - quote.length());
            }
        }
        return upper;
    }
}

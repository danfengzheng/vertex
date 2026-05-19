package com.vertex.service.order.service.impl;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.vertex.api.usersetting.IUserSettingService;
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

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;

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
    /** 用户个人设置（最大使用资金截断），可选注入；user-service 未运行时降级为不限制 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private IUserSettingService userSettingService;

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

    /**
     * 同一持仓的平仓必须串行执行，消除 StopLossTakeProfitTask（10s 轮询线程）与
     * processExitConditions / executeSignal（K 线事件线程）并发触发时的双重平仓竞态。
     * <p>
     * 采用 tryLock 而非 lock：若另一线程正在关闭同一持仓，当前线程直接跳过，无需排队等待。
     * 锁在持仓关闭后通过 remove(key, value) 清理，防止无限积累。
     * </p>
     */
    private final ConcurrentHashMap<Long, ReentrantLock> positionCloseLocks = new ConcurrentHashMap<>();

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
            if (strategy.getExecutionMode() == ExecutionMode.LIVE) {
                // LIVE SPOT 平仓：获取与 executeClose()（Task 止损路径）相同的 per-position 锁，
                // 完全消除两个路径之间的 TOCTOU 竞态。
                // strategyLocks（本方法持有）→ positionCloseLocks（内部再获取），
                // Task 路径只持有 positionCloseLocks，不涉及 strategyLocks，故无死锁风险。
                ReentrantLock posLock = positionCloseLocks.computeIfAbsent(
                        openPosition.getId(), id -> new ReentrantLock(true));
                if (!posLock.tryLock()) {
                    log.info("[Spot] Position {} is already being closed by stop-loss task, skipping SELL signal",
                            openPosition.getId());
                    return;
                }
                try {
                    // 锁内二次确认：防止 Task 在我们拿锁前已完成关单
                    if (!positionManagementService.isStillOpen(openPosition.getId())) {
                        log.info("[Spot] Position {} already closed, skipping SELL signal close",
                                openPosition.getId());
                        return;
                    }
                    BigDecimal closeQty = openPosition.getQuantity();
                    if (closeQty == null || closeQty.compareTo(BigDecimal.ZERO) <= 0) {
                        log.warn("Strategy [{}] has invalid close quantity for LIVE SPOT SELL", strategy.getName());
                        return;
                    }
                    // 在锁内提交订单，与 Task 的 executeCloseInternal() 完全串行
                    submitOpenOrder(strategy, signal, side, closeQty, false, null);
                } finally {
                    posLock.unlock();
                    positionCloseLocks.remove(openPosition.getId(), posLock);
                }
                return;
            }
            // PAPER 模式：handleSell() 内部通过 findOpenPosition(status=OPEN) 自动幂等（已关则返回 null）
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
     *
     * <p><b>并发安全设计：双重防护</b></p>
     * <ol>
     *   <li><b>应用层 per-position 锁</b>（本方法）：tryLock 方式，同一持仓同时只允许一个线程进入；
     *       另一线程持锁时直接跳过，消除 {@code isStillOpen} 与实际平仓之间的 TOCTOU 窗口。</li>
     *   <li><b>数据库层 CAS</b>（{@link PositionManagementService#closePosition}）：
     *       {@code WHERE status=OPEN} 条件更新，即使两个线程均绕过应用层锁（如多节点部署），
     *       也只有一个线程能成功写入，另一个因 rows=0 而放弃后续结算。</li>
     * </ol>
     */
    public void executeClose(Position position) {
        ReentrantLock lock = positionCloseLocks.computeIfAbsent(position.getId(), id -> new ReentrantLock(true));
        if (!lock.tryLock()) {
            log.info("[Close] Position {} is already being closed by another thread, skipping duplicate close",
                    position.getId());
            return;
        }
        try {
            executeCloseInternal(position);
        } finally {
            lock.unlock();
            // 持仓关闭后清理锁对象，remove(key, value) 仅在 value 匹配时移除，防止误删后续线程新建的锁
            positionCloseLocks.remove(position.getId(), lock);
        }
    }

    /**
     * 平仓实际执行逻辑（在 per-position 锁保护下调用）。
     */
    private void executeCloseInternal(Position position) {
        // 在锁保护下再次确认持仓仍为 OPEN（双重检验：应用层串行 + DB 层 CAS 兜底）
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
     * 分阶段止盈：对持仓做部分减仓（中段或 Σ&lt;100% 的末段）。
     * <p>
     * 与 {@link #executeClose(Position)} 共用 per-position 锁（{@code positionCloseLocks}），
     * 确保「部分平 / 全平 / 止损平」三路径串行。
     * </p>
     * <ul>
     *   <li>partialQty 已由调用方按 stepSize 向下对齐（现货）；若 ≥ position.quantity，退化为 executeClose。</li>
     *   <li>inflight 防重：本持仓存在未结算的 reduceOnly/SELL 订单时本轮跳过，等下一次扫描。</li>
     *   <li>fill 回调由 {@link PositionManagementService#updatePosition} 的减仓分支推进
     *       {@code takeProfitStage}，并按 {@code moveStopToBreakevenAfterStage} 触发保本上移。</li>
     * </ul>
     *
     * @param position    要部分平仓的持仓
     * @param partialQty  本次减仓数量（base asset）
     * @param targetStage 本次推进到的目标 stage（1/2/3）
     */
    public void executePartialClose(Position position, BigDecimal partialQty, int targetStage) {
        if (partialQty == null || partialQty.compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("[PartialClose] Invalid qty {} for position {}, skipping", partialQty, position.getId());
            return;
        }
        ReentrantLock lock = positionCloseLocks.computeIfAbsent(position.getId(), id -> new ReentrantLock(true));
        if (!lock.tryLock()) {
            log.info("[PartialClose] Position {} is busy (full close in progress), skip stage {}",
                    position.getId(), targetStage);
            return;
        }
        try {
            executePartialCloseInternal(position, partialQty, targetStage);
        } finally {
            lock.unlock();
        }
    }

    private void executePartialCloseInternal(Position position, BigDecimal partialQty, int targetStage) {
        // 锁内复查：持仓仍 OPEN，stage 没被其他线程推进过
        if (!positionManagementService.isStillOpen(position.getId())) {
            log.info("[PartialClose] Position {} no longer OPEN, skipping stage {}",
                    position.getId(), targetStage);
            return;
        }
        Integer dbStage = positionManagementService.getTakeProfitStage(position.getId());
        int currentStage = dbStage == null ? 0 : dbStage;
        if (currentStage >= targetStage) {
            log.info("[PartialClose] Position {} stage already {} (target {}), skip duplicate",
                    position.getId(), currentStage, targetStage);
            return;
        }

        // inflight 防重：本持仓存在未结算的减仓订单 → 本轮跳过
        if (positionManagementService.hasInflightReduceOrder(position)) {
            log.info("[PartialClose] Position {} has inflight reduce order, skip stage {} this round",
                    position.getId(), targetStage);
            return;
        }

        MarketType marketType = position.getMarketType();
        boolean isFutures = marketType != null && marketType.isFutures();
        BigDecimal remainQty = position.getQuantity();

        // 数量退化：partial >= 剩余 → 直接全平（最后一档扫尾 / 配置异常兜底）
        if (partialQty.compareTo(remainQty) >= 0) {
            log.info("[PartialClose] partialQty {} >= remain {} for position {}, degrade to full close",
                    partialQty, remainQty, position.getId());
            executeCloseInternal(position);
            return;
        }

        // 现货对齐 stepSize（向下截断），合约由 Binance 端对齐
        BigDecimal closeQty = partialQty;
        if (!isFutures) {
            try {
                BigDecimal stepSize = binanceTradeClient.getStepSize(position.getSymbol());
                if (stepSize != null && stepSize.compareTo(BigDecimal.ZERO) > 0) {
                    BigDecimal steps = closeQty.divide(stepSize, 0, RoundingMode.DOWN);
                    closeQty = steps.multiply(stepSize).stripTrailingZeros();
                }
            } catch (Exception e) {
                log.warn("[PartialClose] Failed to align qty for {}: {}, using original {}",
                        position.getSymbol(), e.getMessage(), closeQty);
            }
        }
        if (closeQty == null || closeQty.compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("[PartialClose] Aligned qty is zero for position {}, skip stage {}",
                    position.getId(), targetStage);
            return;
        }
        if (closeQty.compareTo(remainQty) >= 0) {
            // 对齐后仍 >= 剩余，走全平路径
            log.info("[PartialClose] Aligned qty {} >= remain {}, fallback to full close for position {}",
                    closeQty, remainQty, position.getId());
            executeCloseInternal(position);
            return;
        }

        if (position.getTradeMode() == ExecutionMode.PAPER) {
            BigDecimal currentPrice = paperTradingService.getCurrentPrice(
                    position.getExchange(), position.getSymbol());
            if (currentPrice == null) {
                log.warn("[PartialClose] No price for {} {}, skip stage {}",
                        position.getExchange(), position.getSymbol(), targetStage);
                return;
            }
            positionManagementService.reducePosition(position, closeQty, currentPrice, targetStage);
            applyBreakevenIfConfigured(position, targetStage);
            return;
        }

        // LIVE：提交 reduceOnly / SELL 减仓单
        OrderSide closeSide = (isFutures && position.getSide() == PositionSide.SHORT)
                ? OrderSide.BUY : OrderSide.SELL;
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
        // 借用 takeProfitStage 字段透传目标 stage 给 fill 回调（在 updatePosition 减仓分支识别）
        order.setTakeProfitStage(targetStage);
        orderMapper.insert(order);

        log.info("[PartialClose] Submitting live {} reduce {} for position {} stage {}: qty={}",
                closeSide, isFutures ? "(reduceOnly)" : "", position.getId(), targetStage, closeQty);

        doExecute(order, null);

        // doExecute → updatePosition 减仓分支已经推进 stage；此处尝试触发保本钩子
        applyBreakevenIfConfigured(position, targetStage);
    }

    /**
     * 分阶段止盈：触发指定档后，若策略配置了 moveStopToBreakevenAfterStage = newStage，
     * 将止损上移到入场价并把 stopLossStage 标为 BREAKEVEN。
     * 与移动 ATR 止损在配置层已互斥（StrategyServiceImpl 校验）。
     * <p>
     * 调用前提：targetStage 实际推进成功（DB 中 takeProfitStage >= newStage）。
     * 若 LIVE 减仓单失败（REJECTED / 未 fill），fill 回调未推进 stage，本方法不应修改止损。
     * </p>
     */
    private void applyBreakevenIfConfigured(Position position, int newStage) {
        if (position.getStrategyId() == null) return;
        Strategy strategy = strategyRefMapper.selectById(position.getStrategyId());
        if (strategy == null || strategy.getMoveStopToBreakevenAfterStage() == null
                || strategy.getMoveStopToBreakevenAfterStage() <= 0) {
            return;
        }
        if (strategy.getMoveStopToBreakevenAfterStage() != newStage) return;
        // 重新从 DB 读取最新持仓（部分平仓后 quantity / entryPrice 可能已变）
        Position fresh = positionManagementService.getById(position.getId());
        if (fresh == null || fresh.getStatus() != PositionStatus.OPEN) return;
        // 关键校验：stage 必须已经推进到 newStage（防止 LIVE 减仓单失败时误触发保本上移）
        if (fresh.getTakeProfitStage() == null || fresh.getTakeProfitStage() < newStage) {
            log.info("[Staged TP] Breakeven skipped: position={} stage not advanced (db={}, expected>={})",
                    fresh.getId(), fresh.getTakeProfitStage(), newStage);
            return;
        }
        // 幂等：若止损已经在保本价（或更高，譬如已进入 TRAILING），不再下移
        BigDecimal entry = fresh.getEntryPrice();
        if (entry == null) return;
        if (fresh.getStopLossStage() == StopLossStage.BREAKEVEN
                || fresh.getStopLossStage() == StopLossStage.TRAILING) {
            log.debug("[Staged TP] Breakeven idempotent skip: position={} already at stage={}",
                    fresh.getId(), fresh.getStopLossStage());
            return;
        }
        fresh.setStopLoss(entry.setScale(8, RoundingMode.HALF_UP));
        fresh.setStopLossStage(StopLossStage.BREAKEVEN);
        positionManagementService.updateStopLossTakeProfit(fresh);
        log.info("[Staged TP] Breakeven applied: position={} stage={} stopLoss=entry={}",
                fresh.getId(), newStage, entry);
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

        // ── 个人设置：单笔开仓最大使用资金（U/USDT）截断 ──────────
        // 按策略创建者读取 sys_user_setting.max_trade_capital：
        //   > 0 且 tradeAmount 超过此值 → 截断为此值
        //   null / <= 0 / 服务未注入 → 不生效，保持原 tradeAmount
        BigDecimal cappedAmount = applyUserMaxTradeCapital(strategy, tradeAmount);
        if (cappedAmount != null && cappedAmount.compareTo(tradeAmount) < 0) {
            log.info("[PositionSizing] tradeAmount {} exceeds user maxTradeCapital, capped to {}",
                    tradeAmount, cappedAmount);
            tradeAmount = cappedAmount;
        }

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

    /**
     * 按策略创建者读取「单笔开仓最大使用资金」并对 tradeAmount 做截断（仅 PERCENT 仓位模式调用）。
     * <p>
     * - 服务未注入（user-service 未启动）→ 返回原 tradeAmount，不做改动<br>
     * - 用户未配置 / 配置 &lt;= 0 → 返回原 tradeAmount<br>
     * - 用户配置 &gt; 0 且 tradeAmount &gt; max → 返回 max<br>
     * - 其他 → 返回原 tradeAmount
     * </p>
     */
    private BigDecimal applyUserMaxTradeCapital(Strategy strategy, BigDecimal tradeAmount) {
        if (userSettingService == null) return tradeAmount;
        Long ownerId = strategy.getCreateBy();
        if (ownerId == null) return tradeAmount;
        try {
            BigDecimal max = userSettingService.getMaxTradeCapital(ownerId);
            if (max == null || max.compareTo(BigDecimal.ZERO) <= 0) return tradeAmount;
            return tradeAmount.compareTo(max) > 0 ? max : tradeAmount;
        } catch (Exception e) {
            // 个人设置读取失败不阻塞下单，记录日志后保持原值
            log.warn("[PositionSizing] Failed to read user maxTradeCapital for userId={}: {}",
                    ownerId, e.getMessage());
            return tradeAmount;
        }
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
     * 止损优先级：移动ATR止损（四参数） > 固定ATR止损 > 固定百分比止损<br>
     * 止盈优先级：<b>分阶段止盈（size1>0 即启用）</b> &gt; ATR倍数止盈 > 固定百分比止盈
     * （分阶段启用时 ATR / 固定百分比止盈被忽略，互斥语义）。<br>
     * 同时支持 LONG 和 SHORT 持仓方向。
     * </p>
     */
    public void setStopLossTakeProfit(Order order, Strategy strategy) {
        boolean hasTrailingStop = strategy.getInitialStopMultiplier() != null
                && strategy.getInitialStopMultiplier().compareTo(BigDecimal.ZERO) > 0;
        boolean hasAtrStop   = strategy.getAtrStopMultiplier() != null
                && strategy.getAtrStopMultiplier().compareTo(BigDecimal.ZERO) > 0;
        boolean hasPctStop   = strategy.getStopLossPct() != null;
        boolean hasStagedTp  = strategy.getTakeProfitSize1() != null
                && strategy.getTakeProfitSize1().compareTo(BigDecimal.ZERO) > 0
                && strategy.getTakeProfitPct1() != null
                && strategy.getTakeProfitPct1().compareTo(BigDecimal.ZERO) > 0;
        boolean hasAtrTp     = !hasStagedTp
                && strategy.getAtrTakeProfitMultiplier() != null
                && strategy.getAtrTakeProfitMultiplier().compareTo(BigDecimal.ZERO) > 0;
        boolean hasPctTp     = !hasStagedTp && strategy.getTakeProfitPct() != null;

        if (!hasTrailingStop && !hasAtrStop && !hasPctStop
                && !hasAtrTp && !hasPctTp && !hasStagedTp) {
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

        // ── 非ATR路径：固定百分比止盈（仅在未启用分阶段时生效）─────
        if (!hasStagedTp && !hasAtrTp && hasPctTp) {
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

        // ── 分阶段止盈：写入第 1 档为 takeProfit（task 检测下一档触发用），
        //    initialQuantity 作为各档平仓量基准，stage 初始化为 0。─────
        if (hasStagedTp) {
            BigDecimal pct1 = strategy.getTakeProfitPct1()
                    .divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP);
            BigDecimal tp1 = isShort
                    ? entryPrice.multiply(BigDecimal.ONE.subtract(pct1))
                    : entryPrice.multiply(BigDecimal.ONE.add(pct1));
            position.setTakeProfit(tp1.setScale(8, RoundingMode.HALF_UP));
            position.setTakeProfitStage(0);
            if (position.getInitialQuantity() == null
                    || position.getInitialQuantity().compareTo(BigDecimal.ZERO) <= 0) {
                position.setInitialQuantity(position.getQuantity());
            }
            log.info("[Staged TP] strategy={} side={} entry={} initialQty={} stage1=({}%,{}%) tp1={}",
                    strategy.getName(), position.getSide(), entryPrice,
                    position.getInitialQuantity(),
                    strategy.getTakeProfitPct1(), strategy.getTakeProfitSize1(),
                    position.getTakeProfit());
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

    // ─── SuperTrend 动态止损 ──────────────────────────────────────────

    /**
     * K线收盘后更新该策略所有 OPEN 持仓的 SuperTrend 动态止损价。
     * <p>
     * 由 {@link com.vertex.service.order.service.TradeExecutionListenerImpl#onSuperTrendStopUpdate} 触发。
     * <ul>
     *   <li>趋势方向与持仓方向一致时：计算并写入 position.superTrendStopLoss</li>
     *   <li>趋势反转时：跳过，冻结当前止损值，等待其他止损机制或反向信号平仓</li>
     * </ul>
     * </p>
     *
     * @param strategy        策略（含 superTrendSlOffsetPct 配置）
     * @param superTrendValue SuperTrend 当前值（上升=lowerBand；下降=upperBand）
     * @param trendUp         true=上升趋势，false=下降趋势
     */
    public void updateSuperTrendStopLoss(Strategy strategy, BigDecimal superTrendValue, boolean trendUp) {
        if (strategy.getSuperTrendSlOffsetPct() == null
                || strategy.getSuperTrendSlOffsetPct().compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }

        List<Position> openPositions = positionManagementService.findOpenPositionsByStrategy(strategy.getId());
        if (openPositions.isEmpty()) {
            return;
        }

        BigDecimal pct = strategy.getSuperTrendSlOffsetPct()
                .divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP);

        for (Position position : openPositions) {
            try {
                boolean isShort = position.getSide() == PositionSide.SHORT;

                // 趋势反转时冻结止损，不更新
                if (isShort && trendUp) {
                    log.debug("[SuperTrend SL] Trend reversed (up) for SHORT position {}, skipping", position.getId());
                    continue;
                }
                if (!isShort && !trendUp) {
                    log.debug("[SuperTrend SL] Trend reversed (down) for LONG position {}, skipping", position.getId());
                    continue;
                }

                // 多仓：支撑位下方偏移；空仓：阻力位上方偏移
                BigDecimal stopLossPrice = isShort
                        ? superTrendValue.multiply(BigDecimal.ONE.add(pct)).setScale(8, RoundingMode.HALF_UP)
                        : superTrendValue.multiply(BigDecimal.ONE.subtract(pct)).setScale(8, RoundingMode.HALF_UP);

                // 止损价只向有利方向移动（LONG 只升不降 / SHORT 只降不升），防止 ATR 扩张时止损倒退
                BigDecimal existing = position.getSuperTrendStopLoss();
                boolean shouldUpdate = existing == null
                        || (isShort  && stopLossPrice.compareTo(existing) < 0)   // SHORT：止损价下降才更新
                        || (!isShort && stopLossPrice.compareTo(existing) > 0);  // LONG ：止损价上升才更新
                if (!shouldUpdate) {
                    log.debug("[SuperTrend SL] Skipped (unfavorable move): position={} existing={} new={}",
                            position.getId(), existing, stopLossPrice);
                    continue;
                }

                position.setSuperTrendStopLoss(stopLossPrice);
                positionManagementService.updateSuperTrendStopLoss(position);
                log.info("[SuperTrend SL] Updated: strategy={} position={} side={} superTrend={} trendUp={} stopLoss={}",
                        strategy.getName(), position.getId(), position.getSide(),
                        superTrendValue, trendUp, stopLossPrice);
            } catch (Exception e) {
                log.error("[SuperTrend SL] Error updating position {}: {}", position.getId(), e.getMessage(), e);
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
                // 1. 更新持仓K线计数（列级精确写入，避免覆盖并发更新的 superTrendStopLoss 等字段）
                int barCount = (pos.getOpenBarCount() != null ? pos.getOpenBarCount() : 0) + 1;
                pos.setOpenBarCount(barCount);
                positionManagementService.updateOpenBarCount(pos);

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
        // 兜底：用有效止损价与入场价的方向关系判断是否亏损
        // LONG  SL < entryPrice → 亏损区; LONG  SL >= entryPrice → 移动止损/SuperTrend已锁利
        // SHORT SL > entryPrice → 亏损区; SHORT SL <= entryPrice → 移动止损/SuperTrend已锁利
        // 优先使用 superTrendStopLoss（仅配置了SuperTrend止损、没有固定SL时生效）
        BigDecimal effectiveSl = position.getStopLoss() != null
                ? position.getStopLoss()
                : position.getSuperTrendStopLoss();
        if (position.getEntryPrice() != null && effectiveSl != null) {
            boolean isShort = position.getSide() == PositionSide.SHORT;
            return isShort
                    ? effectiveSl.compareTo(position.getEntryPrice()) > 0
                    : effectiveSl.compareTo(position.getEntryPrice()) < 0;
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
            // 列级更新：只写 tradingPausedUntil，避免 updateById 覆盖并发修改的其他字段（如 enabled）
            strategyRefMapper.update(null,
                    new LambdaUpdateWrapper<Strategy>()
                            .eq(Strategy::getId, strategy.getId())
                            .set(Strategy::getTradingPausedUntil, pauseUntil));
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

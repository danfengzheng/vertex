package com.vertex.service.farb.execution;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.vertex.service.farb.entity.FarbPosition;
import com.vertex.service.farb.entity.FarbStrategy;
import com.vertex.service.farb.mapper.FarbPositionMapper;
import com.vertex.model.entity.trading.MarketType;
import com.vertex.service.farb.regime.ArbCandidate;
import com.vertex.service.farb.regime.RegimeMetrics;
import com.vertex.service.farb.service.FarbEventLogger;
import com.vertex.service.farb.signal.FarbSignalCache;
import com.vertex.service.order.client.BinanceFuturesClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 资金费套利 - 双腿并发下单 + Saga 补偿。
 * <p>
 * DESIGN §6.2：
 * <ul>
 *   <li>并发提交 spot BUY + perp SELL</li>
 *   <li>都成功 → 记账 pair OPEN</li>
 *   <li>一腿失败 → 立即回滚已成功那腿</li>
 *   <li>都失败 → 无副作用</li>
 *   <li>超时 → REST 查真实状态决定</li>
 * </ul>
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PairExecutor {

    private static final long SAGA_TIMEOUT_MS = 8000;   // 两腿同时下单超时（Binance 通常 <2s）

    private final SpotPerpOrderClient orderClient;
    private final PaperOrderSimulator paperSim;
    private final FarbCredentialResolver credResolver;
    private final FarbPositionMapper positionMapper;
    private final com.vertex.service.farb.mapper.FarbStrategyMapper strategyMapper;
    private final FarbEventLogger eventLogger;
    private final FarbSignalCache signalCache;
    private final BinanceFuturesClient futuresClient;
    private final DualMakerExecutor dualMaker;

    /** 默认 dual-maker post-only timeout / poll 间隔（毫秒），可被 strategy 覆盖 */
    private static final long DEFAULT_DUAL_MAKER_TIMEOUT_MS = 5000;
    private static final long DEFAULT_DUAL_MAKER_POLL_MS = 300;
    /** 出场时哪些 trigger 直接跳过 dual-maker 走 MARKET（时间敏感）*/
    private static final java.util.Set<String> EMERGENCY_TRIGGERS = java.util.Set.of(
            "BASIS_EMERGENCY", "LIQUIDATION_RISK", "EMERGENCY");

    /** 已设过 leverage 的 (accountId, symbol)，避免重复调用 */
    private final java.util.Set<String> leverageSetCache = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Saga 用的并发线程池（每对 pair 需两个 slot 并发跑两腿）*/
    private final ExecutorService sagaPool = Executors.newFixedThreadPool(20, r -> {
        Thread t = new Thread(r, "farb-saga");
        t.setDaemon(true);
        return t;
    });

    // ─── 入场 openPair ──────────────────────────────────────────────────

    /**
     * 尝试为策略入场一对新的套利仓。返回创建的 FarbPosition 或 null（失败）。
     */
    @Transactional(rollbackFor = Exception.class, propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public FarbPosition openPair(FarbStrategy strategy, ArbCandidate candidate) {
        String symbol = candidate.getSymbol();
        boolean isPaper = "PAPER".equalsIgnoreCase(strategy.getExecutionMode());

        // 1. 预置 pair 记录（PENDING_ENTRY）
        FarbPosition pair = preparePosition(strategy, candidate);
        positionMapper.insert(pair);

        // 2. 解析凭证
        FarbCredentialResolver.Credentials spotCreds;
        FarbCredentialResolver.Credentials perpCreds;
        try {
            spotCreds = credResolver.resolveSpotOrPaper(strategy.getSpotAccountId(), isPaper);
            perpCreds = credResolver.resolvePerpOrPaper(strategy.getPerpAccountId(), isPaper);
        } catch (Exception ex) {
            markZombie(pair, "credential_resolve_failed: " + ex.getMessage());
            eventLogger.positionError(strategy.getId(), pair.getId(), symbol,
                    "PAIR_OPEN_CREDENTIAL_FAILED", Map.of("error", ex.getMessage()));
            return null;
        }

        // 3. 计算下单量：notional / spot_price → qty，再对齐 stepSize（依赖 BinanceTradeClient 自动 align）
        BigDecimal notional = strategy.getNotionalPerPair();
        BigDecimal spotPrice = candidate.getMetrics() != null && candidate.getMetrics().getCurrentBasisBps() >= 0
                ? spotPriceFromMetrics(candidate.getMetrics())
                : null;
        if (spotPrice == null || spotPrice.compareTo(BigDecimal.ZERO) <= 0) {
            markZombie(pair, "missing_price_estimate");
            eventLogger.positionError(strategy.getId(), pair.getId(), symbol,
                    "PAIR_OPEN_NO_PRICE", Map.of("error", "missing price for size calc"));
            return null;
        }
        BigDecimal qty = notional.divide(spotPrice, 8, RoundingMode.HALF_UP);

        // 3b. 设杠杆（LIVE 才调，PAPER 不 mock）
        if (!isPaper && strategy.getPerpLeverage() != null) {
            ensureLeverage(strategy.getPerpAccountId(), symbol, strategy.getPerpLeverage(), perpCreds);
        }

        eventLogger.positionInfo(strategy.getId(), pair.getId(), symbol,
                "PAIR_OPEN_ATTEMPT", Map.of("notional", notional, "qty", qty, "isPaper", isPaper));

        // 4. Saga：并发下双腿（LIVE + 启用 dual-maker → 先试 post-only，失败降级 taker）
        SagaOutcome outcome;
        if (!isPaper && isDualMakerEnabled(strategy)) {
            DualMakerExecutor.DualMakerOutcome dm = dualMaker.executeEntry(
                    spotCreds, perpCreds, symbol, qty,
                    makerFee(strategy.getSpotMakerFeeRate(), strategy.getSpotFeeRate()),
                    makerFee(strategy.getPerpMakerFeeRate(), strategy.getPerpFeeRate()),
                    strategy.getSpotFeeRate(), strategy.getPerpFeeRate(),
                    dualMakerTimeoutMs(strategy), dualMakerPollMs(strategy));
            if (dm.success()) {
                eventLogger.positionInfo(strategy.getId(), pair.getId(), symbol,
                        "PAIR_OPEN_DUAL_MAKER", Map.of("route", dm.route()));
                outcome = SagaOutcome.success(dm.spot(), dm.perp());
            } else if (dm.needsFallback() || "FAIL".equals(dm.route())) {
                // needsFallback（超时都没成）或 FAIL（book/place 前置失败）→ 都可以安全 fallback，两腿都没成
                log.info("[PairExecutor] dual-maker fallback → full taker sym={} route={} reason={}",
                        symbol, dm.route(), dm.errorMessage());
                outcome = executeSaga(strategy, symbol, qty, spotCreds, perpCreds);
            } else {
                // LEG_RISK：一腿已成、另一腿追市价失败 → 应急回滚已成腿（尝试反向平掉），避免裸露
                PairOrderResult filled = dm.spot() != null ? dm.spot() : dm.perp();
                boolean isSpotFilled = dm.spot() != null;
                PairOrderResult rollback = attemptEntryRollback(isSpotFilled, filled, strategy,
                        symbol, spotCreds, perpCreds);
                boolean rollbackOk = rollback != null && rollback.isSuccess();
                if (rollbackOk) {
                    pair.setStatus("CANCELED");
                    pair.setCloseTrigger("DUAL_MAKER_LEG_RISK_ROLLED_BACK");
                    pair.setCloseTime(java.time.LocalDateTime.now());
                    positionMapper.updateById(pair);
                } else {
                    markZombie(pair, "dual_maker_leg_risk_rollback_failed: " + dm.errorMessage());
                    // 回滚失败 = 单腿裸露 → kill switch：立即暂停策略
                    pauseStrategyKillSwitch(strategy, "ENTRY_LEG_RISK_ROLLBACK_FAILED");
                }
                eventLogger.positionError(strategy.getId(), pair.getId(), symbol,
                        "PAIR_OPEN_DUAL_MAKER_LEG_RISK", Map.of(
                                "route", dm.route(),
                                "reason", dm.errorMessage(),
                                "filledLeg", isSpotFilled ? "SPOT" : "PERP",
                                "filledQty", filled.getExecutedQty(),
                                "rollbackOk", rollbackOk,
                                "strategyPaused", !rollbackOk));
                return null;
            }
        } else {
            outcome = executeSaga(strategy, symbol, qty, spotCreds, perpCreds);
        }

        // 5. 处理结果
        if (outcome.bothSuccess()) {
            fillEntryDetails(pair, outcome);
            pair.setStatus("OPEN");
            positionMapper.updateById(pair);
            eventLogger.positionInfo(strategy.getId(), pair.getId(), symbol,
                    "PAIR_OPEN_SUCCESS", entryPayload(outcome));
            return pair;
        }
        // 一腿失败：saga 已回滚。清理 pair
        markZombie(pair, outcome.describeFailure());
        eventLogger.positionError(strategy.getId(), pair.getId(), symbol,
                "PAIR_OPEN_ROLLBACK", Map.of("reason", outcome.describeFailure()));
        return null;
    }

    // ─── 出场 closePair ─────────────────────────────────────────────────

    /**
     * 平仓：并发下 spot SELL + perp BUY reduceOnly。失败时重试 3 次。
     */
    public boolean closePair(FarbStrategy strategy, FarbPosition pair, String trigger) {
        boolean isPaper = "PAPER".equalsIgnoreCase(strategy.getExecutionMode());
        FarbCredentialResolver.Credentials spotCreds = credResolver.resolveSpotOrPaper(
                strategy.getSpotAccountId(), isPaper);
        FarbCredentialResolver.Credentials perpCreds = credResolver.resolvePerpOrPaper(
                strategy.getPerpAccountId(), isPaper);

        pair.setStatus("CLOSING");
        pair.setCloseTrigger(trigger);
        positionMapper.updateById(pair);

        eventLogger.positionInfo(strategy.getId(), pair.getId(), pair.getSymbol(),
                "PAIR_CLOSE_ATTEMPT", Map.of("trigger", trigger, "spotQty", pair.getSpotQty(),
                        "perpQty", pair.getPerpQty()));

        // 强平保护场景 → 先平 perp（受威胁的腿），成功后再平 spot；避免"spot 已卖但 perp 单腿裸露"
        boolean perpFirst = "LIQUIDATION_RISK".equals(trigger);
        boolean emergency = EMERGENCY_TRIGGERS.contains(trigger);

        // 常规出场（非 emergency）+ LIVE + 启用 dual-maker → 先尝试 post-only
        if (!isPaper && !emergency && isDualMakerEnabled(strategy)) {
            DualMakerExecutor.DualMakerOutcome dm = dualMaker.executeExit(
                    spotCreds, perpCreds, pair.getSymbol(),
                    pair.getSpotQty(), pair.getPerpQty(),   // 分开传，避免因 stepSize 差异导致不平
                    makerFee(strategy.getSpotMakerFeeRate(), strategy.getSpotFeeRate()),
                    makerFee(strategy.getPerpMakerFeeRate(), strategy.getPerpFeeRate()),
                    strategy.getSpotFeeRate(), strategy.getPerpFeeRate(),
                    dualMakerTimeoutMs(strategy), dualMakerPollMs(strategy));
            if (dm.success()) {
                fillExitDetails(pair, dm.spot(), dm.perp());
                computePnl(pair, strategy);
                positionMapper.updateById(pair);
                eventLogger.positionInfo(strategy.getId(), pair.getId(), pair.getSymbol(),
                        "PAIR_CLOSE_SUCCESS", Map.of("route", dm.route(), "trigger", trigger,
                                "netPnl", pair.getNetPnl()));
                return true;
            }
            if ("LEG_RISK".equals(dm.route())) {
                // 一腿已平、另一腿追市价失败 → 不能再下单，直接 ZOMBIE + 停策略（防扩大风险）
                pair.setStatus("ZOMBIE");
                positionMapper.updateById(pair);
                pauseStrategyKillSwitch(strategy, "EXIT_LEG_RISK");
                eventLogger.positionError(strategy.getId(), pair.getId(), pair.getSymbol(),
                        "PAIR_CLOSE_DUAL_MAKER_LEG_RISK",
                        Map.of("trigger", trigger, "route", dm.route(), "reason", dm.errorMessage(),
                               "strategyPaused", true));
                return false;
            }
            log.info("[PairExecutor] close dual-maker fallback → taker sym={} route={} reason={}",
                    pair.getSymbol(), dm.route(), dm.errorMessage());
            // needsFallback / FAIL → 继续走下面的 taker 重试
        }

        // 出场 saga：跟踪已成功的腿，避免对已成交腿重复下单
        PairOrderResult spotDone = null;
        PairOrderResult perpDone = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                if (perpFirst) {
                    // 危险场景：先保命腿（perp），再平 spot
                    if (perpDone == null || !perpDone.isSuccess()) {
                        perpDone = closePerpLeg(isPaper, strategy, pair, perpCreds);
                    }
                    if (perpDone != null && perpDone.isSuccess()
                            && (spotDone == null || !spotDone.isSuccess())) {
                        spotDone = closeSpotLeg(isPaper, strategy, pair, spotCreds);
                    }
                } else {
                    // 常规场景：并发下两腿，速度优先
                    CompletableFuture<PairOrderResult> sFut = spotDone != null && spotDone.isSuccess()
                            ? CompletableFuture.completedFuture(spotDone)
                            : CompletableFuture.supplyAsync(
                                    () -> closeSpotLeg(isPaper, strategy, pair, spotCreds), sagaPool);
                    CompletableFuture<PairOrderResult> pFut = perpDone != null && perpDone.isSuccess()
                            ? CompletableFuture.completedFuture(perpDone)
                            : CompletableFuture.supplyAsync(
                                    () -> closePerpLeg(isPaper, strategy, pair, perpCreds), sagaPool);
                    spotDone = sFut.get(SAGA_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                    perpDone = pFut.get(SAGA_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                }

                boolean spotOk = spotDone != null && spotDone.isSuccess();
                boolean perpOk = perpDone != null && perpDone.isSuccess();
                if (spotOk && perpOk) {
                    fillExitDetails(pair, spotDone, perpDone);
                    computePnl(pair, strategy);
                    positionMapper.updateById(pair);
                    eventLogger.positionInfo(strategy.getId(), pair.getId(), pair.getSymbol(),
                            "PAIR_CLOSE_SUCCESS", Map.of("attempt", attempt,
                                    "netPnl", pair.getNetPnl(),
                                    "fundingPnl", pair.getFundingPnl(),
                                    "basisPnl", pair.getBasisPnl()));
                    return true;
                }
                log.warn("[PairExecutor] close attempt={} sym={} spotOk={} perpOk={} perpFirst={}",
                        attempt, pair.getSymbol(), spotOk, perpOk, perpFirst);
            } catch (Exception ex) {
                log.warn("[PairExecutor] close attempt={} sym={} exception: {}",
                        attempt, pair.getSymbol(), ex.getMessage());
            }
        }
        // 三次都失败 → ZOMBIE 人工处理
        pair.setStatus("ZOMBIE");
        positionMapper.updateById(pair);
        eventLogger.positionError(strategy.getId(), pair.getId(), pair.getSymbol(),
                "PAIR_CLOSE_ZOMBIE", Map.of("trigger", trigger,
                        "spotClosed", spotDone != null && spotDone.isSuccess(),
                        "perpClosed", perpDone != null && perpDone.isSuccess(),
                        "advice", "两腿平仓失败 3 次，需人工到 Binance 手动平"));
        return false;
    }

    private PairOrderResult closeSpotLeg(boolean isPaper, FarbStrategy strategy, FarbPosition pair,
                                          FarbCredentialResolver.Credentials creds) {
        return isPaper
                ? paperSim.spotSell(pair.getSymbol(), pair.getSpotQty(), strategy.getSpotFeeRate())
                : orderClient.spotSellMarket(creds.apiKey(), creds.apiSecret(),
                        pair.getSymbol(), pair.getSpotQty(), strategy.getSpotFeeRate());
    }

    private PairOrderResult closePerpLeg(boolean isPaper, FarbStrategy strategy, FarbPosition pair,
                                          FarbCredentialResolver.Credentials creds) {
        return isPaper
                ? paperSim.perpBuyReduceOnly(pair.getSymbol(), pair.getPerpQty(), strategy.getPerpFeeRate())
                : orderClient.perpBuyReduceOnly(creds.apiKey(), creds.apiSecret(),
                        pair.getSymbol(), pair.getPerpQty(), strategy.getPerpFeeRate());
    }

    // ─── Saga 核心 ──────────────────────────────────────────────────────

    private SagaOutcome executeSaga(FarbStrategy strategy, String symbol, BigDecimal qty,
                                     FarbCredentialResolver.Credentials spotCreds,
                                     FarbCredentialResolver.Credentials perpCreds) {
        boolean isPaper = "PAPER".equalsIgnoreCase(strategy.getExecutionMode());
        CompletableFuture<PairOrderResult> spotFut = CompletableFuture.supplyAsync(() ->
                isPaper
                    ? paperSim.spotBuy(symbol, qty, strategy.getSpotFeeRate())
                    : orderClient.spotBuyMarket(spotCreds.apiKey(), spotCreds.apiSecret(),
                            symbol, qty, strategy.getSpotFeeRate()), sagaPool);
        CompletableFuture<PairOrderResult> perpFut = CompletableFuture.supplyAsync(() ->
                isPaper
                    ? paperSim.perpSell(symbol, qty, strategy.getPerpFeeRate())
                    : orderClient.perpSellMarket(perpCreds.apiKey(), perpCreds.apiSecret(),
                            symbol, qty, strategy.getPerpFeeRate()), sagaPool);

        PairOrderResult spot, perp;
        try {
            spot = spotFut.get(SAGA_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (Exception ex) {
            spot = PairOrderResult.builder().success(false)
                    .errorMessage("spot_leg_timeout_or_exception: " + ex.getMessage()).build();
        }
        try {
            perp = perpFut.get(SAGA_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (Exception ex) {
            perp = PairOrderResult.builder().success(false)
                    .errorMessage("perp_leg_timeout_or_exception: " + ex.getMessage()).build();
        }

        if (spot.isSuccess() && perp.isSuccess()) {
            return SagaOutcome.success(spot, perp);
        }

        // 一腿成功一腿失败 → 立即回滚
        if (spot.isSuccess() && !perp.isSuccess()) {
            log.warn("[PairExecutor] saga rollback: spot OK perp FAILED sym={} → SELL back spot",
                    symbol);
            PairOrderResult rollback = isPaper
                    ? paperSim.spotSell(symbol, spot.getExecutedQty(), strategy.getSpotFeeRate())
                    : orderClient.spotSellMarket(spotCreds.apiKey(), spotCreds.apiSecret(),
                            symbol, spot.getExecutedQty(), strategy.getSpotFeeRate());
            return SagaOutcome.rollback("spot_ok_perp_fail: " + perp.getErrorMessage(),
                    rollback.isSuccess(), spot, perp);
        }
        if (!spot.isSuccess() && perp.isSuccess()) {
            log.warn("[PairExecutor] saga rollback: perp OK spot FAILED sym={} → BUY back perp",
                    symbol);
            PairOrderResult rollback = isPaper
                    ? paperSim.perpBuyReduceOnly(symbol, perp.getExecutedQty(), strategy.getPerpFeeRate())
                    : orderClient.perpBuyReduceOnly(perpCreds.apiKey(), perpCreds.apiSecret(),
                            symbol, perp.getExecutedQty(), strategy.getPerpFeeRate());
            return SagaOutcome.rollback("perp_ok_spot_fail: " + spot.getErrorMessage(),
                    rollback.isSuccess(), spot, perp);
        }
        // both failed → no side effect
        return SagaOutcome.bothFailed(spot, perp);
    }

    // ─── 记账辅助 ───────────────────────────────────────────────────────

    private FarbPosition preparePosition(FarbStrategy strategy, ArbCandidate candidate) {
        FarbPosition p = new FarbPosition();
        p.setStrategyId(strategy.getId());
        p.setSymbol(candidate.getSymbol());
        p.setStatus("PENDING_ENTRY");
        p.setEntryTime(LocalDateTime.now());
        p.setNotionalAtEntry(strategy.getNotionalPerPair());
        RegimeMetrics m = candidate.getMetrics();
        if (m != null) {
            p.setEntryBasisBps(BigDecimal.valueOf(m.getCurrentBasisBps())
                    .setScale(2, RoundingMode.HALF_UP));
            // Fix #3: 反归一化 latest funding bps 到真实 rate（考虑真实结算周期）
            int intervalHours = 8;
            var recentFunding = signalCache.recentFunding(candidate.getSymbol(), 1);
            if (!recentFunding.isEmpty()) {
                intervalHours = recentFunding.get(0).value().intervalHours();
            }
            double rawRate = m.getLatestFundingBps() / 10000.0 * intervalHours / 8.0;
            p.setEntryFundingRate(BigDecimal.valueOf(rawRate).setScale(10, RoundingMode.HALF_UP));
            p.setEntryRegimeSnapshot(JSON.toJSONString(m));
            long nextSettleTs = m.getSymbol() != null ? extractNextSettle(candidate) : 0;
            if (nextSettleTs > 0) {
                p.setNextExpectedSettlementTime(
                        LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(nextSettleTs),
                                java.time.ZoneId.systemDefault()));
            }
        }
        p.setProjectedNetYieldPct(BigDecimal.valueOf(candidate.getExpectedNetYieldPct())
                .setScale(4, RoundingMode.HALF_UP));
        p.setSpotQty(BigDecimal.ZERO);
        p.setPerpQty(BigDecimal.ZERO);
        p.setActualFundingPnl(BigDecimal.ZERO);
        p.setSettlementCount(0);
        return p;
    }

    private void fillEntryDetails(FarbPosition pair, SagaOutcome o) {
        pair.setSpotQty(o.spot().getExecutedQty());
        pair.setPerpQty(o.perp().getExecutedQty());
        pair.setEntrySpotPrice(o.spot().getAvgFillPrice());
        pair.setEntryPerpPrice(o.perp().getAvgFillPrice());
        pair.setEntrySpotOrderId(o.spot().getOrderId());
        pair.setEntrySpotClientId(o.spot().getClientOrderId());
        pair.setEntryPerpOrderId(o.perp().getOrderId());
        pair.setEntryPerpClientId(o.perp().getClientOrderId());
        pair.setEntrySpotFee(o.spot().getFeePaid());
        pair.setEntryPerpFee(o.perp().getFeePaid());
    }

    private void fillExitDetails(FarbPosition pair, PairOrderResult spot, PairOrderResult perp) {
        pair.setExitSpotOrderId(spot.getOrderId());
        pair.setExitPerpOrderId(perp.getOrderId());
        pair.setExitSpotPrice(spot.getAvgFillPrice());
        pair.setExitPerpPrice(perp.getAvgFillPrice());
        pair.setExitSpotFee(spot.getFeePaid());
        pair.setExitPerpFee(perp.getFeePaid());
        pair.setCloseTime(LocalDateTime.now());
        pair.setStatus("CLOSED");
    }

    /** P&L 三段归因（DESIGN §8.3）*/
    private void computePnl(FarbPosition pair, FarbStrategy strategy) {
        BigDecimal fundingPnl = pair.getActualFundingPnl() != null
                ? pair.getActualFundingPnl() : BigDecimal.ZERO;

        // direction pnl = (exit_spot - entry_spot) × spot_qty - (exit_perp - entry_perp) × perp_qty
        BigDecimal spotDelta = pair.getExitSpotPrice().subtract(pair.getEntrySpotPrice())
                .multiply(pair.getSpotQty());
        BigDecimal perpDelta = pair.getExitPerpPrice().subtract(pair.getEntryPerpPrice())
                .multiply(pair.getPerpQty());
        BigDecimal directionPnl = spotDelta.subtract(perpDelta);

        BigDecimal feeTotal = zero(pair.getEntrySpotFee()).add(zero(pair.getEntryPerpFee()))
                .add(zero(pair.getExitSpotFee())).add(zero(pair.getExitPerpFee()));

        BigDecimal netPnl = fundingPnl.add(directionPnl).subtract(feeTotal);

        pair.setFundingPnl(fundingPnl);
        pair.setBasisPnl(directionPnl);
        pair.setFeePaid(feeTotal);
        pair.setNetPnl(netPnl);
    }

    private BigDecimal zero(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }

    /**
     * Sprint 5：从 SignalCache 拿 premium 记录的 indexPrice 作为现货价估算。
     * <p>Binance USDM 的 indexPrice 就是从多家现货加权得到，接近 spot mid。误差 <5 bps 可接受。
     * 后续若需精确 spot ask，可加 SpotBookTickerSubscriber。</p>
     */
    private BigDecimal spotPriceFromMetrics(RegimeMetrics m) {
        var prem = signalCache.latestPremium(m.getSymbol());
        if (prem == null || prem.value().indexPrice() <= 0) return null;
        return BigDecimal.valueOf(prem.value().indexPrice());
    }

    /** Sprint 5：从 SignalCache 拿 nextSettleTs */
    private long extractNextSettle(ArbCandidate c) {
        var prem = signalCache.latestPremium(c.getSymbol());
        return prem == null ? 0L : prem.value().nextSettleTs();
    }

    /**
     * Sprint 5：入场前调用一次 setLeverage，同 (account, symbol) 只调一次。
     * 失败仅记 warn 不阻塞下单（币安未设杠杆时默认 20x，可接受）。
     */
    private void ensureLeverage(long accountId, String symbol, int leverage,
                                 FarbCredentialResolver.Credentials creds) {
        String cacheKey = accountId + ":" + symbol + ":" + leverage;
        if (leverageSetCache.contains(cacheKey)) return;
        try {
            futuresClient.setLeverage(creds.apiKey(), creds.apiSecret(), symbol, leverage, MarketType.USDM);
            leverageSetCache.add(cacheKey);
            log.info("[PairExecutor] setLeverage account={} symbol={} lev={}", accountId, symbol, leverage);
        } catch (Exception ex) {
            log.warn("[PairExecutor] setLeverage failed account={} symbol={} lev={}: {}",
                    accountId, symbol, leverage, ex.getMessage());
        }
    }

    private void markZombie(FarbPosition p, String reason) {
        p.setStatus("ZOMBIE");
        p.setCloseTrigger("OPEN_FAILED:" + reason.substring(0, Math.min(reason.length(), 100)));
        p.setCloseTime(LocalDateTime.now());   // Fix #4: 补 closeTime，前端统计正确
        positionMapper.updateById(p);
    }

    private Map<String, Object> entryPayload(SagaOutcome o) {
        Map<String, Object> m = new HashMap<>();
        m.put("spotQty", o.spot().getExecutedQty());
        m.put("perpQty", o.perp().getExecutedQty());
        m.put("spotPrice", o.spot().getAvgFillPrice());
        m.put("perpPrice", o.perp().getAvgFillPrice());
        m.put("spotFee", o.spot().getFeePaid());
        m.put("perpFee", o.perp().getFeePaid());
        return m;
    }

    // ─── Kill switch ────────────────────────────────────────────

    /**
     * 遇到裸腿风险时立即暂停策略，防止 RegimeScanner 继续开新单扩大损失。
     * 只降级 status = PAUSED，不清 pair —— 人工核对 Binance 实际持仓后手动恢复。
     */
    private void pauseStrategyKillSwitch(FarbStrategy strategy, String reason) {
        try {
            if (!"RUNNING".equals(strategy.getStatus())) return;   // 已经不在运行
            com.vertex.service.farb.entity.FarbStrategy patch = new com.vertex.service.farb.entity.FarbStrategy();
            patch.setId(strategy.getId());
            patch.setStatus("PAUSED");
            strategyMapper.updateById(patch);
            strategy.setStatus("PAUSED");   // 内存视图同步
            log.error("[KillSwitch] strategy={} PAUSED reason={}", strategy.getId(), reason);
            eventLogger.error(strategy.getId(), "KILL_SWITCH_TRIGGERED",
                    Map.of("reason", reason, "advice", "请人工核对 Binance 账户持仓 vs pair 表，处理后手动恢复策略"));
        } catch (Exception ex) {
            log.error("[KillSwitch] pause failed strategy={}: {}", strategy.getId(), ex.getMessage(), ex);
        }
    }

    // ─── Dual-maker helpers ────────────────────────────────────────────

    /**
     * 入场 LEG_RISK 应急回滚：如果 spot 已经买入但 perp 追空失败 → 立即 MARKET SELL spot 平回来。
     * 反之亦然。避免留下裸腿。
     */
    private PairOrderResult attemptEntryRollback(boolean isSpotFilled, PairOrderResult filled,
                                                  FarbStrategy strategy, String symbol,
                                                  FarbCredentialResolver.Credentials spotCreds,
                                                  FarbCredentialResolver.Credentials perpCreds) {
        try {
            if (isSpotFilled) {
                // spot 已 BUY → SELL 平回
                return orderClient.spotSellMarket(spotCreds.apiKey(), spotCreds.apiSecret(),
                        symbol, filled.getExecutedQty(), strategy.getSpotFeeRate());
            } else {
                // perp 已 SELL 开空 → BUY reduceOnly 平回
                return orderClient.perpBuyReduceOnly(perpCreds.apiKey(), perpCreds.apiSecret(),
                        symbol, filled.getExecutedQty(), strategy.getPerpFeeRate());
            }
        } catch (Exception ex) {
            log.error("[PairExecutor] emergency rollback FAILED sym={} isSpot={} err={}",
                    symbol, isSpotFilled, ex.getMessage());
            return PairOrderResult.builder().success(false).errorMessage(ex.getMessage()).build();
        }
    }

    private boolean isDualMakerEnabled(FarbStrategy s) {
        return s.getDualMakerEnabled() != null && s.getDualMakerEnabled() == 1;
    }
    private long dualMakerTimeoutMs(FarbStrategy s) {
        return s.getDualMakerTimeoutMs() != null && s.getDualMakerTimeoutMs() > 0
                ? s.getDualMakerTimeoutMs() : DEFAULT_DUAL_MAKER_TIMEOUT_MS;
    }
    private long dualMakerPollMs(FarbStrategy s) {
        return s.getDualMakerPollMs() != null && s.getDualMakerPollMs() > 0
                ? s.getDualMakerPollMs() : DEFAULT_DUAL_MAKER_POLL_MS;
    }
    /** 取 maker fee，若策略未配置则退回 taker fee（等价禁用 maker 优惠）*/
    private BigDecimal makerFee(BigDecimal makerRate, BigDecimal takerRate) {
        return makerRate != null && makerRate.compareTo(BigDecimal.ZERO) >= 0 ? makerRate : takerRate;
    }

    // ─── Saga 结果对象 ─────────────────────────────────────────────────

    private record SagaOutcome(PairOrderResult spot, PairOrderResult perp,
                                boolean bothSuccess, boolean rolledBack, String rollbackDesc) {
        static SagaOutcome success(PairOrderResult s, PairOrderResult p) {
            return new SagaOutcome(s, p, true, false, null);
        }
        static SagaOutcome rollback(String desc, boolean rollbackOk, PairOrderResult s, PairOrderResult p) {
            return new SagaOutcome(s, p, false, rollbackOk, desc);
        }
        static SagaOutcome bothFailed(PairOrderResult s, PairOrderResult p) {
            return new SagaOutcome(s, p, false, false, "both_legs_failed");
        }
        String describeFailure() {
            if (rollbackDesc != null) return rollbackDesc + (rolledBack ? " [rolledBack=OK]" : " [ROLLBACK_FAILED]");
            return "both_failed spot=" + spot.getErrorMessage() + " perp=" + perp.getErrorMessage();
        }
    }
}

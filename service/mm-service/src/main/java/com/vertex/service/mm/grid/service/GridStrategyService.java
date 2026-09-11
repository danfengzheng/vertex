package com.vertex.service.mm.grid.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.vertex.service.mm.grid.entity.GridConfigHistory;
import com.vertex.service.mm.grid.entity.GridStrategy;
import com.vertex.service.mm.grid.mapper.GridConfigHistoryMapper;
import com.vertex.service.mm.grid.mapper.GridStrategyMapper;
import com.vertex.service.mm.market.BookTickerSubscriber;
import com.vertex.service.mm.userstream.UserDataStreamManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 网格策略 CRUD + 生命周期。
 * <p>
 * 生命周期状态机：
 * <pre>
 *   STOPPED ──start()──→ RUNNING ──stop()──→ STOPPED
 *                          │
 *                          ├──emergencyStop()──→ EMERGENCY_STOPPED（人工恢复）
 *                          └──pause()──→ PAUSED（撤挂单，保留持仓）
 * </pre>
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GridStrategyService {

    private final GridStrategyMapper strategyMapper;
    private final GridConfigHistoryMapper historyMapper;
    private final SlidingWindowManager slidingWindow;
    private final OrderIntentExecutor executor;
    private final GridEngine gridEngine;
    private final GridEventLogger eventLogger;
    private final UserDataStreamManager userStreamManager;
    private final BookTickerSubscriber bookTickerSubscriber;
    private final AccountCredentialResolver credentialResolver;
    private final org.springframework.beans.factory.ObjectProvider<WsReconciliationService> reconciliationProvider;
    private final org.springframework.beans.factory.ObjectProvider<AccountRiskAggregator> accountRiskProvider;
    private final org.springframework.beans.factory.ObjectProvider<EmergencyStopService> emergencyProvider;
    private final com.vertex.service.mm.market.DepthSubscriber depthSubscriber;
    private final com.vertex.service.mm.market.AggTradeSubscriber aggTradeSubscriber;
    private final com.vertex.service.mm.paper.PaperFillSimulator paperFillSimulator;
    private final com.vertex.service.mm.paper.PaperOrderClient paperOrderClient;
    private final com.vertex.service.mm.binance.BinanceAccountClient binanceAccountClient;
    private final com.vertex.service.mm.grid.mapper.GridOrderMapper gridOrderMapper;

    private volatile boolean listenersRegistered = false;
    private volatile boolean paperListenersRegistered = false;

    /** 首次 start 时注册所有 UserStreamListener（避免重复注册；Manager 内部用 CopyOnWriteArrayList）*/
    public synchronized void ensureListenersRegistered() {
        if (listenersRegistered) return;
        userStreamManager.addListener(gridEngine);
        var reconciliation = reconciliationProvider.getIfAvailable();
        if (reconciliation != null) userStreamManager.addListener(reconciliation);
        var accountRisk = accountRiskProvider.getIfAvailable();
        if (accountRisk != null) userStreamManager.addListener(accountRisk);
        listenersRegistered = true;
    }

    /** Paper 模式下把 PaperFillSimulator 接入 aggTrade 流 + 让它把合成事件送给 GridEngine */
    public synchronized void ensurePaperListenersRegistered() {
        if (paperListenersRegistered) return;
        aggTradeSubscriber.addListener(paperFillSimulator);
        paperFillSimulator.addListener(gridEngine);
        // Layer 5 账户级熔断也监听 paper 事件（虽然 paper 不真发 ACCOUNT_UPDATE，但 order fill 影响 delta 计算）
        var accountRisk = accountRiskProvider.getIfAvailable();
        if (accountRisk != null) paperFillSimulator.addListener(accountRisk);
        paperListenersRegistered = true;
    }

    // ─── CRUD ────────────────────────────────────────────────

    public GridStrategy get(long id) {
        return strategyMapper.selectById(id);
    }

    public List<GridStrategy> list() {
        return strategyMapper.selectList(new LambdaQueryWrapper<GridStrategy>()
                .orderByDesc(GridStrategy::getCreateTime));
    }

    public List<GridStrategy> listRunning() {
        return strategyMapper.selectList(new LambdaQueryWrapper<GridStrategy>()
                .eq(GridStrategy::getStatus, "RUNNING"));
    }

    @Transactional(rollbackFor = Exception.class)
    public Long create(GridStrategy dto) {
        applyDefaults(dto);
        if (dto.getStatus() == null) dto.setStatus("STOPPED");
        strategyMapper.insert(dto);
        log.info("[GridStrategyService] created strategy id={} name={} symbol={}",
                dto.getId(), dto.getName(), dto.getSymbol());
        return dto.getId();
    }

    @Transactional(rollbackFor = Exception.class)
    public void update(long id, GridStrategy patch, Long updateBy) {
        GridStrategy existing = strategyMapper.selectById(id);
        if (existing == null) throw new IllegalArgumentException("strategy not found: " + id);

        // 防御：runtime 字段不允许通过 PUT 修改（避免前端 form 回填时误清零运行时状态）
        clearRuntimeFields(patch);

        // 记录变更（简化：只记录 patch 里 非 null 且和原值不同的字段）
        recordChanges(id, existing, patch, updateBy);

        patch.setId(id);
        strategyMapper.updateById(patch);
    }

    /**
     * 首次启动或 WS 刚建连时，quote cache 可能还没数据。轮询等待最多 5 秒。
     * @return 中枢价或 null（超时）
     */
    private BigDecimal resolveCenterWithWait(String symbol, BigDecimal userProvided) {
        BigDecimal center = gridEngine.suggestInitialCenter(symbol, userProvided);
        if (center != null && center.compareTo(BigDecimal.ZERO) > 0) return center;

        long deadline = System.currentTimeMillis() + 5000L;
        int attempts = 0;
        while (System.currentTimeMillis() < deadline) {
            try { Thread.sleep(200L); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
            attempts++;
            center = gridEngine.suggestInitialCenter(symbol, userProvided);
            if (center != null && center.compareTo(BigDecimal.ZERO) > 0) {
                log.info("[GridStrategyService] symbol={} got quote after {} attempts (~{}ms)",
                        symbol, attempts, attempts * 200);
                return center;
            }
        }
        log.warn("[GridStrategyService] symbol={} no quote after 5s wait", symbol);
        return null;
    }

    /** 清空所有非用户可编辑字段（运行时快照 + 生命周期状态）*/
    private void clearRuntimeFields(GridStrategy patch) {
        patch.setStatus(null);
        patch.setCenterPrice(null);
        patch.setStepPct(null);
        patch.setLastReshuffleAt(null);
        patch.setLastAdaptiveAt(null);
        patch.setLastLayerChangeAt(null);
        patch.setCurrentActiveLayer(null);
        patch.setCurrentLongQty(null);
        patch.setCurrentShortQty(null);
        patch.setTotalArbitragePnl(null);
        patch.setTotalDirectionalPnl(null);
        patch.setTotalFeePaid(null);
    }

    public void delete(long id) {
        GridStrategy s = strategyMapper.selectById(id);
        if (s == null) return;
        if ("RUNNING".equals(s.getStatus())) {
            throw new IllegalStateException("cannot delete RUNNING strategy, stop first");
        }
        strategyMapper.deleteById(id);
    }

    // ─── 生命周期 ────────────────────────────────────────────

    /**
     * 启动策略：订阅 WS + 初始化挂单矩阵 + 挂满 window。
     * <p>
     * <b>不加 @Transactional</b> —— 内部有 5s BookTicker 等待和 N 个 Binance REST，不能锁 DB 行；
     * 状态转换通过独立的短事务在小步骤完成，崩溃时依赖 GridRecoveryService 从 DB 已持久化的
     * centerPrice + grid_order 恢复。
     * </p>
     */
    public void start(long id, BigDecimal initialCenterPrice) {
        GridStrategy strategy = strategyMapper.selectById(id);
        if (strategy == null) throw new IllegalArgumentException("strategy not found: " + id);
        if ("RUNNING".equals(strategy.getStatus())) {
            log.info("[GridStrategyService] strategy={} already RUNNING", id);
            return;
        }
        if ("EMERGENCY_STOPPED".equals(strategy.getStatus())) {
            throw new IllegalStateException("strategy in EMERGENCY_STOPPED, manual recovery required");
        }

        BigDecimal step = strategy.getBaseStepPct();
        if (step == null || step.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalStateException("baseStepPct not set");
        }

        // 订阅市场数据（PAPER + LIVE 都需要）—— 先订阅再取报价避免"先鸡后蛋"
        bookTickerSubscriber.subscribe(strategy.getSymbol());
        depthSubscriber.subscribe(strategy.getSymbol());

        BigDecimal center = resolveCenterWithWait(strategy.getSymbol(), initialCenterPrice);
        if (center == null || center.compareTo(BigDecimal.ZERO) <= 0) {
            bookTickerSubscriber.unsubscribe(strategy.getSymbol());
            depthSubscriber.unsubscribe(strategy.getSymbol());
            throw new IllegalStateException(
                    "cannot determine initial center price after 5s wait for BookTicker on symbol="
                            + strategy.getSymbol() + ". Check symbol name and network. "
                            + "Or provide initialCenterPrice manually.");
        }

        boolean isPaper = "PAPER".equalsIgnoreCase(strategy.getExecutionMode());
        var creds = credentialResolver.resolveOrPaper(strategy.getAccountId(), isPaper);

        if (isPaper) {
            // 防御：清理任何遗留的 LIVE 记录（PaperOrderBook 内存已重置，DB 里的 LIVE 都是僵尸）
            int stale = cleanupStalePaperOrders(id);
            if (stale > 0) {
                log.warn("[GridStrategyService] cleaned up {} stale PAPER LIVE orders on start", stale);
            }
            aggTradeSubscriber.subscribe(strategy.getSymbol());
            if (strategy.getPaperQueueLossFactor() != null) {
                paperFillSimulator.setQueueLossFactor(strategy.getSymbol(), strategy.getPaperQueueLossFactor());
            }
            if (strategy.getPaperGtxRejectRate() != null) {
                paperOrderClient.setGtxRejectRate(strategy.getSymbol(), strategy.getPaperGtxRejectRate());
            }
            ensurePaperListenersRegistered();
            log.info("[GridStrategyService] strategy={} started in PAPER mode", id);
        } else {
            // LIVE 账户预设（幂等）—— 首次接触 symbol 时确保交易所侧配置和 DB 一致
            if (!binanceAccountClient.ensureDualSideMode(creds.apiKey(), creds.apiSecret())) {
                log.warn("[GridStrategyService] strategy={} failed to ensure dual position mode — orders may be rejected", id);
            }
            int leverage = strategy.getLeverage() == null ? 3 : strategy.getLeverage();
            binanceAccountClient.setLeverage(creds.apiKey(), creds.apiSecret(),
                    strategy.getSymbol(), leverage);
            String marginType = strategy.getMarginType() == null ? "ISOLATED" : strategy.getMarginType();
            binanceAccountClient.setMarginType(creds.apiKey(), creds.apiSecret(),
                    strategy.getSymbol(), marginType);

            userStreamManager.start(strategy.getAccountId(), creds.apiKey());
            ensureListenersRegistered();
        }

        // 初始化挂单矩阵（内存 + 生成 intents，不下单）
        var intents = slidingWindow.initialize(strategy, center, step);
        if (intents.isEmpty()) {
            // qty 被 stepSize round 到 0 或所有侧都禁用 → 提前失败，不做半程状态
            slidingWindow.removeState(id);
            bookTickerSubscriber.unsubscribe(strategy.getSymbol());
            depthSubscriber.unsubscribe(strategy.getSymbol());
            if (isPaper) aggTradeSubscriber.unsubscribe(strategy.getSymbol());
            throw new IllegalStateException(
                    "no orders to place — check qtyPerGrid (may be < stepSize) or side_enabled flags");
        }

        // ★ 关键：先持久化 RUNNING + center/step，再挂单。
        // 如果挂单中途崩溃，恢复时 GridRecoveryService 能从 DB 拿到 centerPrice 重建状态，
        // WsReconciliation 兜底对账真实挂单。
        persistRunningState(strategy, center, step);

        // 挂单执行（独立于 DB 事务；REST 失败允许部分挂上）
        var summary = executor.execute(intents, creds.apiKey(), creds.apiSecret());
        for (var intent : intents) {
            if (intent.getClientOrderId() != null) {
                gridEngine.trackClientOrder(intent.getClientOrderId(), id);
            }
        }

        eventLogger.info(id, "START", Map.of(
                "center", center, "step", step,
                "placeSuccess", summary.placeSuccess,
                "placeFailed", summary.placeFailed,
                "gtxRejected", summary.gtxRejected));
        log.info("[GridStrategyService] strategy={} started: center={} step={} {}",
                id, center, step, summary);
    }

    /** PAPER 启动前把遗留 LIVE 订单标 CANCELED（防止 UI 挂单数翻倍） */
    private int cleanupStalePaperOrders(long strategyId) {
        try {
            com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<
                    com.vertex.service.mm.grid.entity.GridOrder> w =
                    new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<>();
            w.eq(com.vertex.service.mm.grid.entity.GridOrder::getGridStrategyId, strategyId)
             .eq(com.vertex.service.mm.grid.entity.GridOrder::getStatus, "LIVE")
             .set(com.vertex.service.mm.grid.entity.GridOrder::getStatus, "CANCELED")
             .set(com.vertex.service.mm.grid.entity.GridOrder::getCanceledAt, LocalDateTime.now());
            return gridOrderMapper.update(null, w);
        } catch (Exception ex) {
            log.warn("[GridStrategyService] cleanup stale paper orders failed: {}", ex.getMessage());
            return 0;
        }
    }

    /**
     * 持久化启动状态 —— 单表单行 update，MySQL 隐式事务足够。
     * 不加 @Transactional 是有意的：同类内调用会绕过 Spring 代理，加了反而误导。
     */
    private void persistRunningState(GridStrategy strategy, BigDecimal center, BigDecimal step) {
        strategy.setStatus("RUNNING");
        strategy.setCenterPrice(center);
        strategy.setStepPct(step);
        strategy.setLastReshuffleAt(LocalDateTime.now());
        strategy.setCurrentActiveLayer("NORMAL");
        strategyMapper.updateById(strategy);
    }

    /**
     * 停止（SOFT_STOP）：撤所有挂单，保留持仓。
     * <p>无 @Transactional：撤单是 N 个 REST 调用，不能锁 DB。</p>
     */
    public void stop(long id) {
        GridStrategy strategy = strategyMapper.selectById(id);
        if (strategy == null) return;
        if ("STOPPED".equals(strategy.getStatus())) return;

        var creds = credentialResolver.resolveOrPaper(strategy.getAccountId(),
                "PAPER".equalsIgnoreCase(strategy.getExecutionMode()));
        var intents = slidingWindow.cancelAll(strategy);
        var summary = executor.execute(intents, creds.apiKey(), creds.apiSecret());

        slidingWindow.removeState(id);
        // 三个 subscriber 都是 refcount，会在最后一个订阅者退订时才真正关闭连接
        bookTickerSubscriber.unsubscribe(strategy.getSymbol());
        depthSubscriber.unsubscribe(strategy.getSymbol());
        if ("PAPER".equalsIgnoreCase(strategy.getExecutionMode())) {
            aggTradeSubscriber.unsubscribe(strategy.getSymbol());
        }
        // 注意：UserDataStream 一个账户可能被多策略共用，此处不 stop

        strategy.setStatus("STOPPED");
        strategyMapper.updateById(strategy);

        eventLogger.info(id, "STOP", Map.of("cancelSummary", summary.toString()));
        log.info("[GridStrategyService] strategy={} stopped (SOFT). {}", id, summary);
    }

    /**
     * 紧急停机。委托给 {@link EmergencyStopService}（如可用）实现 4 种模式：
     * SOFT_STOP / GRACEFUL_CLOSE / LIMIT_LADDER_CLOSE / MARKET_FALLBACK。
     */
    public void emergencyStop(long id, String reason) {
        emergencyStop(id, "SOFT_STOP", reason);
    }

    /**
     * 从 EMERGENCY_STOPPED 显式恢复到 STOPPED —— 人工确认已排查问题后调用。
     * 恢复后可以正常 {@link #start} 重启。
     */
    public void recoverFromEmergency(long id, Long operatorId, String note) {
        GridStrategy strategy = strategyMapper.selectById(id);
        if (strategy == null) throw new IllegalArgumentException("strategy not found: " + id);
        if (!"EMERGENCY_STOPPED".equals(strategy.getStatus())) {
            throw new IllegalStateException("strategy not in EMERGENCY_STOPPED (current=" + strategy.getStatus() + ")");
        }
        strategy.setStatus("STOPPED");
        // 清一次 layer / delta 快照，避免下次启动时被 stale 数据误判
        strategy.setCurrentActiveLayer("NORMAL");
        strategyMapper.updateById(strategy);
        eventLogger.warn(id, "EMERGENCY_RECOVERED", java.util.Map.of(
                "operator", operatorId == null ? "unknown" : operatorId,
                "note", note == null ? "" : note));
        log.warn("[GridStrategyService] strategy={} recovered from EMERGENCY_STOPPED by operator={}", id, operatorId);
    }

    public void emergencyStop(long id, String mode, String reason) {
        var svc = emergencyProvider.getIfAvailable();
        if (svc != null) {
            svc.execute(id, mode, reason);
            return;
        }
        // 兜底：模块最小可用（无 EmergencyStopService）— 撤单 + 改状态
        GridStrategy strategy = strategyMapper.selectById(id);
        if (strategy == null) return;
        try {
            var creds = credentialResolver.resolveOrPaper(strategy.getAccountId(),
                "PAPER".equalsIgnoreCase(strategy.getExecutionMode()));
            var intents = slidingWindow.cancelAll(strategy);
            executor.execute(intents, creds.apiKey(), creds.apiSecret());
            slidingWindow.removeState(id);
        } catch (Exception ex) {
            log.error("[GridStrategyService] fallback emergency stop error strategy={}: {}", id, ex.getMessage());
        }
        strategy.setStatus("EMERGENCY_STOPPED");
        strategyMapper.updateById(strategy);
        eventLogger.error(id, "EMERGENCY_STOP", Map.of("reason", reason, "mode", mode));
    }

    // ─── 助手 ────────────────────────────────────────────────

    private void applyDefaults(GridStrategy s) {
        if (s.getStatus() == null) s.setStatus("STOPPED");
        if (s.getHedgeMode() == null) s.setHedgeMode(1);
        if (s.getLongSideEnabled() == null) s.setLongSideEnabled(1);
        if (s.getShortSideEnabled() == null) s.setShortSideEnabled(1);
        if (s.getAdaptiveStepEnabled() == null) s.setAdaptiveStepEnabled(1);
        if (s.getTrendProtectionEnabled() == null) s.setTrendProtectionEnabled(1);
        if (s.getWindowSizePerSide() == null) s.setWindowSizePerSide(5);
        if (s.getBaseStepPct() == null) s.setBaseStepPct(new BigDecimal("0.8"));
        if (s.getMinimumStepSafetyMultiplier() == null) s.setMinimumStepSafetyMultiplier(new BigDecimal("5.0"));
        if (s.getLeverage() == null) s.setLeverage(3);
        if (s.getMarginType() == null) s.setMarginType("ISOLATED");
        if (s.getExecutionMode() == null) s.setExecutionMode("PAPER");
        if (s.getPaperQueueLossFactor() == null) s.setPaperQueueLossFactor(new BigDecimal("0.5"));
        if (s.getPaperGtxRejectRate() == null) s.setPaperGtxRejectRate(new BigDecimal("0.05"));
        if (s.getPaperSmartPricingEnabled() == null) s.setPaperSmartPricingEnabled(1);
        if (s.getPaperMinLiquidityQty() == null) s.setPaperMinLiquidityQty(new BigDecimal("2.0"));
        // 风控自动停默认关闭 —— 用户需显式开启才生效（避免默认 5% 静默触发的隐藏坑）
        if (s.getRiskAutoStopEnabled() == null) s.setRiskAutoStopEnabled(0);
    }

    private void recordChanges(long strategyId, GridStrategy before, GridStrategy after, Long updateBy) {
        try {
            java.lang.reflect.Field[] fields = GridStrategy.class.getDeclaredFields();
            for (var f : fields) {
                f.setAccessible(true);
                Object newVal = f.get(after);
                if (newVal == null) continue;
                Object oldVal = f.get(before);
                if (java.util.Objects.equals(oldVal, newVal)) continue;
                GridConfigHistory h = new GridConfigHistory();
                h.setGridStrategyId(strategyId);
                h.setFieldChanged(f.getName());
                h.setOldValue(oldVal == null ? null : oldVal.toString());
                h.setNewValue(newVal.toString());
                h.setChangeSource("USER");
                h.setChangedBy(updateBy);
                h.setChangedAt(LocalDateTime.now());
                historyMapper.insert(h);
            }
        } catch (Exception ex) {
            log.warn("[GridStrategyService] recordChanges failed: {}", ex.getMessage());
        }
    }
}

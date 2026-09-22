package com.vertex.service.mm.grid.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.vertex.service.mm.grid.entity.GridOrder;
import com.vertex.service.mm.grid.entity.GridStrategy;
import com.vertex.service.mm.grid.mapper.GridOrderMapper;
import com.vertex.service.mm.grid.mapper.GridStrategyMapper;
import com.vertex.service.mm.market.MarketQuote;
import com.vertex.service.mm.market.MarketQuoteCache;
import com.vertex.service.mm.risk.DepletionState;
import com.vertex.service.mm.risk.TrendScoreEvaluator;
import com.vertex.service.mm.risk.WindowDepletionAnalyzer;
import com.vertex.service.mm.userstream.UserStreamListener;
import com.vertex.service.mm.userstream.event.AccountUpdateEvent;
import com.vertex.service.mm.userstream.event.OrderTradeUpdateEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 网格核心事件循环。
 * <p>
 * 实现 {@link UserStreamListener}，收到私有流事件后：
 * <ol>
 *   <li>更新 SlidingWindow 状态</li>
 *   <li>WindowDepletionAnalyzer 记录事件</li>
 *   <li>生成补挂/撤单意图</li>
 *   <li>综合评分 → 判断保护层级变化</li>
 *   <li>OrderIntentExecutor 执行</li>
 * </ol>
 * </p>
 * <p>
 * 相关组件：AdaptiveScheduler（5min 定时步长/中枢）、ProactiveReducer（Layer 3 减仓）、
 * SideStopService（Layer 4 分侧停）、AccountRiskAggregator（Layer 5 账户熔断）、
 * WsReconciliationService（WS 重连对账）均已在 Phase 2 独立实现并注册为 UserStreamListener。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GridEngine implements UserStreamListener {

    private final GridStrategyMapper strategyMapper;
    private final GridOrderMapper orderMapper;
    private final SlidingWindowManager slidingWindow;
    private final OrderIntentExecutor executor;
    private final WindowDepletionAnalyzer depletionAnalyzer;
    private final TrendScoreEvaluator scoreEvaluator;
    private final MarketQuoteCache marketQuoteCache;
    private final GridEventLogger eventLogger;
    private final AccountCredentialResolver credentialResolver;
    private final OrderPairMatcher pairMatcher;
    private final TrendIndicatorCache trendIndicatorCache;

    /** clientOrderId → grid_strategy_id 的映射（用于快速定位归属策略） */
    private final ConcurrentMap<String, Long> clientOrderToStrategy = new ConcurrentHashMap<>();

    /** evaluateTrendScore 每策略最小间隔（100ms），高频成交时节流避免重复算分 */
    private static final long TREND_EVAL_MIN_INTERVAL_MS = 100L;
    private final ConcurrentMap<Long, java.util.concurrent.atomic.AtomicLong> lastTrendEvalAt =
            new ConcurrentHashMap<>();

    /**
     * 注册 clientOrderId 归属 —— 挂单时调用。
     */
    public void trackClientOrder(String clientOrderId, long strategyId) {
        if (clientOrderId != null) clientOrderToStrategy.put(clientOrderId, strategyId);
    }

    // ─── UserStreamListener ─────────────────────────────────

    @Override
    public void onOrderUpdate(OrderTradeUpdateEvent event) {
        // 只处理成交事件（TRADE），忽略 NEW / CANCELED / EXPIRED 等状态变更
        if (!"TRADE".equalsIgnoreCase(event.getExecType())) return;
        if (event.getLastFilledQty() == null
                || event.getLastFilledQty().compareTo(BigDecimal.ZERO) <= 0) return;

        // 找到归属策略
        Long strategyId = clientOrderToStrategy.get(event.getClientOrderId());
        if (strategyId == null) {
            // 有可能是重连后收到的 stale event，从 DB 反查
            GridOrder order = orderMapper.selectOne(new LambdaQueryWrapper<GridOrder>()
                    .eq(GridOrder::getClientOrderId, event.getClientOrderId())
                    .last("LIMIT 1"));
            if (order == null) {
                log.debug("[GridEngine] fill event unknown clientOrderId={}", event.getClientOrderId());
                return;
            }
            strategyId = order.getGridStrategyId();
            clientOrderToStrategy.put(event.getClientOrderId(), strategyId);
        }

        GridStrategy strategy = strategyMapper.selectById(strategyId);
        if (strategy == null) return;

        // 更新 DB 订单状态
        try {
            com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<GridOrder> w =
                    new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<>();
            w.eq(GridOrder::getClientOrderId, event.getClientOrderId())
             .set(GridOrder::getStatus, "FILLED".equalsIgnoreCase(event.getOrderStatus()) ? "FILLED" : "PARTIAL")
             .set(GridOrder::getFilledQty, event.getCumulativeFilledQty())
             .set(GridOrder::getAvgFillPrice, event.getLastFilledPrice())
             .set(GridOrder::getFeePaid, event.getFee())
             .set(GridOrder::getFilledAt, LocalDateTime.now());
            orderMapper.update(null, w);
        } catch (Exception ex) {
            log.warn("[GridEngine] update grid_order failed clientOrderId={}: {}",
                    event.getClientOrderId(), ex.getMessage());
        }

        // 查询原始 order 拿 role / gridIndex
        GridOrder original = orderMapper.selectOne(new LambdaQueryWrapper<GridOrder>()
                .eq(GridOrder::getClientOrderId, event.getClientOrderId())
                .last("LIMIT 1"));
        if (original == null) return;

        boolean isOpen = "OPEN".equalsIgnoreCase(original.getRole());
        if (isOpen) {
            depletionAnalyzer.onOpenFill(strategyId, event.getPositionSide(), event.getEventTimeMs());
        } else {
            depletionAnalyzer.onCloseFill(strategyId, event.getPositionSide(), event.getEventTimeMs());
        }

        // 事件日志
        Map<String, Object> payload = new HashMap<>();
        payload.put("clientOrderId", event.getClientOrderId());
        payload.put("side", event.getSide());
        payload.put("positionSide", event.getPositionSide());
        payload.put("role", original.getRole());
        payload.put("price", event.getLastFilledPrice());
        payload.put("qty", event.getLastFilledQty());
        payload.put("isMaker", event.isMaker());
        eventLogger.info(strategyId, "FILL", payload);

        // CLOSE 单成交 → 触发 P&L 归因
        if (!isOpen) {
            try {
                pairMatcher.onCloseFilled(strategy, original);
            } catch (Exception ex) {
                log.warn("[GridEngine] pair match error strategy={}: {}", strategyId, ex.getMessage());
            }
        }

        // V1: OPEN 成交 → 挂 CLOSE + 标记 HELD；CLOSE 成交 → 释放 HELD + 重挂 OPEN
        // 两种情况都要过 SlidingWindow 才能维持 SlotState 三态机（DESIGN_V1_SIMPLE §1.4）
        {
            SlidingWindowManager.FillResponse response = slidingWindow.onOrderFilled(
                    strategy, original.getGridPrice(), event.getSide(),
                    event.getPositionSide(), original.getRole(),
                    original.getGridIndex() == null ? 0 : original.getGridIndex(),
                    event.getLastFilledQty(), event.getLastFilledPrice());

            if (!response.intents().isEmpty()) {
                try {
                    var creds = credentialResolver.resolveOrPaper(strategy.getAccountId(),
                            "PAPER".equalsIgnoreCase(strategy.getExecutionMode()));
                    var summary = executor.execute(response.intents(), creds.apiKey(), creds.apiSecret());
                    log.info("[GridEngine] strategy={} fill-response executed: {}", strategyId, summary);
                    // 回填 clientOrderToStrategy 映射
                    for (var intent : response.intents()) {
                        if (intent.getClientOrderId() != null) {
                            clientOrderToStrategy.put(intent.getClientOrderId(), strategyId);
                        }
                    }
                } catch (Exception ex) {
                    log.error("[GridEngine] execute fill-response failed strategy={}: {}",
                            strategyId, ex.getMessage(), ex);
                }
            }

            if (response.suggestReshuffle()) {
                log.info("[GridEngine] strategy={} suggested reshuffle, price drift detected", strategyId);
                // Reshuffle 由 AdaptiveScheduler 的 5min 定时任务处理，此处只记录
                eventLogger.info(strategyId, "RESHUFFLE_SUGGESTED", Map.of(
                        "fillPrice", event.getLastFilledPrice()));
            }
        }

        // 每次 fill 都重新评估 trend score（低成本）
        evaluateTrendScore(strategy);
    }

    @Override
    public void onAccountUpdate(AccountUpdateEvent event) {
        // 高频事件（可 10s 内数十次）—— 只写内存 GridState，PnlSnapshotScheduler 每分钟批量 sync 到 DB。
        // UI 通过 /status 端点从 SlidingWindowManager.getState 读实时值。
        if (event.getPositions() == null) return;
        for (var pos : event.getPositions()) {
            if (pos.getPositionAmt() == null) continue;
            List<GridStrategy> strategies = strategyMapper.selectList(new LambdaQueryWrapper<GridStrategy>()
                    .eq(GridStrategy::getSymbol, pos.getSymbol())
                    .eq(GridStrategy::getStatus, "RUNNING"));
            for (GridStrategy s : strategies) {
                GridState state = slidingWindow.getState(s.getId());
                if (state == null) continue;
                BigDecimal absQty = pos.getPositionAmt().abs();
                if ("LONG".equalsIgnoreCase(pos.getPositionSide())) {
                    state.setCurrentLongQty(absQty);
                } else if ("SHORT".equalsIgnoreCase(pos.getPositionSide())) {
                    state.setCurrentShortQty(absQty);
                }
            }
        }
    }

    @Override
    public void onMarginCall(String rawPayload) {
        log.error("[GridEngine] MARGIN_CALL received: {}", rawPayload);
        // 账户级熔断由 AccountRiskAggregator 独立监听 + 触发 EmergencyStopService
    }

    @Override
    public void onReconnected() {
        // 全量对账由 WsReconciliationService 独立监听 + 处理
        log.info("[GridEngine] WS reconnected (reconciliation handled by WsReconciliationService)");
    }

    @Override
    public void onListenKeyExpired() {
        log.warn("[GridEngine] listenKey expired → UserDataStreamManager 会自动重连");
    }

    // ─── 综合评分 ────────────────────────────────────────────

    private void evaluateTrendScore(GridStrategy strategy) {
        // 节流：同策略 100ms 内的重复调用直接跳过（fill 热路径每 tick 都会命中）
        long now = System.currentTimeMillis();
        var lastRef = lastTrendEvalAt.computeIfAbsent(strategy.getId(),
                k -> new java.util.concurrent.atomic.AtomicLong(0));
        long last = lastRef.get();
        if (now - last < TREND_EVAL_MIN_INTERVAL_MS) return;
        if (!lastRef.compareAndSet(last, now)) return;   // 并发丢弃

        GridState state = slidingWindow.getState(strategy.getId());
        if (state == null) return;

        DepletionState depletion = depletionAnalyzer.compute(strategy.getId(),
                state.countLongOpenOrders(),
                state.countShortOpenOrders(),
                state.getWindowSizePerSide());

        // ADX 从 TrendIndicatorCache 拿（AdaptiveScheduler 每 5min 刷新，成交热路径不重算）
        BigDecimal adx = trendIndicatorCache.getAdx(strategy.getId());
        DepletionState scored = scoreEvaluator.evaluate(strategy, depletion, adx);
        String newLayer = scoreEvaluator.mapScoreToLayer(strategy, scored.getTrendScore());

        String prevLayer = state.getCurrentLayer().get();
        if (!newLayer.equals(prevLayer)) {
            if (state.getCurrentLayer().compareAndSet(prevLayer, newLayer)) {
                state.setLastLayerChangeAt(LocalDateTime.now());
                Map<String, Object> payload = new HashMap<>();
                payload.put("from", prevLayer);
                payload.put("to", newLayer);
                payload.put("score", scored.getTrendScore());
                payload.put("depletion_long", scored.getLongDepletionPct());
                payload.put("depletion_short", scored.getShortDepletionPct());
                payload.put("asymmetry", scored.getAsymmetryRatio());
                payload.put("consecutive", scored.getConsecutiveSameSide());
                eventLogger.warn(strategy.getId(), "LAYER_CHANGE", payload);
                log.info("[GridEngine] strategy={} layer {} → {} (score={})",
                        strategy.getId(), prevLayer, newLayer, scored.getTrendScore());
            }
        }
    }

    /**
     * 冷启动：根据当前 bid/ask 建议初始中枢价。
     */
    public BigDecimal suggestInitialCenter(String symbol, BigDecimal userProvided) {
        if (userProvided != null && userProvided.compareTo(BigDecimal.ZERO) > 0) return userProvided;
        MarketQuote quote = marketQuoteCache.get(symbol);
        if (quote != null) return quote.mid();
        return null;
    }
}

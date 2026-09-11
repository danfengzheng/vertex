package com.vertex.service.mm.grid.service;

import com.vertex.service.mm.binance.MmOrderRequest;
import com.vertex.service.mm.binance.MmOrderResult;
import com.vertex.service.mm.grid.entity.GridStrategy;
import com.vertex.service.mm.grid.mapper.GridStrategyMapper;
import com.vertex.service.mm.market.MarketQuote;
import com.vertex.service.mm.market.MarketQuoteCache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 紧急停机服务 —— 4 种模式的完整实现。
 * <p>
 * 模式：
 * <ul>
 *   <li><b>SOFT_STOP</b>：撤所有挂单，保留持仓（用户自己处理）</li>
 *   <li><b>GRACEFUL_CLOSE</b>：撤挂单 + GTX 挂"贴价"平仓（追单 5s / 次）</li>
 *   <li><b>LIMIT_LADDER_CLOSE</b>：撤挂单 + 分档 GTX 挂平仓（[bid, bid-0.1%, bid-0.2%, ...]）</li>
 *   <li><b>MARKET_FALLBACK</b>：撤挂单 + 用户明确启用 taker → 市价强平（唯一破例）</li>
 * </ul>
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmergencyStopService {

    private final GridStrategyMapper strategyMapper;
    private final SlidingWindowManager slidingWindow;
    private final OrderIntentExecutor executor;
    private final ProactiveReducer reducer;
    private final AccountCredentialResolver credentialResolver;
    private final GridEventLogger eventLogger;
    private final PositionInfoResolver positionResolver;
    private final MarketQuoteCache marketQuoteCache;
    private final OrderClientRouter router;
    private final com.vertex.service.mm.market.BookTickerSubscriber bookTickerSubscriber;
    private final com.vertex.service.mm.market.DepthSubscriber depthSubscriber;
    private final com.vertex.service.mm.market.AggTradeSubscriber aggTradeSubscriber;

    /**
     * @param strategyId 策略
     * @param mode SOFT_STOP / GRACEFUL_CLOSE / LIMIT_LADDER_CLOSE / MARKET_FALLBACK
     * @param reason 触发理由（日志）
     */
    public void execute(long strategyId, String mode, String reason) {
        GridStrategy strategy = strategyMapper.selectById(strategyId);
        if (strategy == null) {
            log.warn("[Emergency] strategy={} not found", strategyId);
            return;
        }

        String normalizedMode = mode == null ? "SOFT_STOP" : mode.toUpperCase();
        Map<String, Object> payload = new HashMap<>();
        payload.put("mode", normalizedMode);
        payload.put("reason", reason);
        eventLogger.error(strategyId, "EMERGENCY_STOP", payload);
        log.warn("[Emergency] strategy={} mode={} reason={}", strategyId, normalizedMode, reason);

        try {
            // Step 1: 撤所有挂单（所有模式都做）
            var creds = credentialResolver.resolveOrPaper(strategy.getAccountId(),
                    "PAPER".equalsIgnoreCase(strategy.getExecutionMode()));
            var cancelIntents = slidingWindow.cancelAll(strategy);
            if (!cancelIntents.isEmpty()) {
                executor.execute(cancelIntents, creds.apiKey(), creds.apiSecret());
            }
            slidingWindow.removeState(strategyId);

            // Step 2: 按模式处理仓位
            switch (normalizedMode) {
                case "SOFT_STOP" -> {
                    // 只撤单，不平仓
                    log.info("[Emergency] SOFT_STOP done, positions preserved");
                }
                case "GRACEFUL_CLOSE" -> {
                    // 追价 GTX 平仓：ProactiveReducer 单次挂单 + 5s 追价 × 24 = 2min
                    reducer.reduce(strategy, "LONG", new BigDecimal("100"));
                    reducer.reduce(strategy, "SHORT", new BigDecimal("100"));
                }
                case "LIMIT_LADDER_CLOSE" -> {
                    // 分档 GTX 挂平仓：一次挂多档，等待被吃（成交率高但价格更差）
                    placeLadderClose(strategy, "LONG", creds);
                    placeLadderClose(strategy, "SHORT", creds);
                }
                case "MARKET_FALLBACK" -> {
                    // 用户明确选择 MARKET → 视为已授权 taker，无需再检查 emergencyTakerFallbackEnabled
                    // （该 flag 只用于 ProactiveReducer 的自动 escape hatch）
                    marketFallback(strategy, "LONG", creds);
                    marketFallback(strategy, "SHORT", creds);
                }
                default -> log.warn("[Emergency] unknown mode: {}, treating as SOFT_STOP", normalizedMode);
            }

            // Step 3: 退订 WS（refcount）—— 与 stop() 保持一致
            try {
                bookTickerSubscriber.unsubscribe(strategy.getSymbol());
                depthSubscriber.unsubscribe(strategy.getSymbol());
                if ("PAPER".equalsIgnoreCase(strategy.getExecutionMode())) {
                    aggTradeSubscriber.unsubscribe(strategy.getSymbol());
                }
            } catch (Exception ex) {
                log.warn("[Emergency] unsubscribe error: {}", ex.getMessage());
            }

            // Step 4: 状态改 EMERGENCY_STOPPED
            strategy.setStatus("EMERGENCY_STOPPED");
            strategyMapper.updateById(strategy);
        } catch (Exception ex) {
            log.error("[Emergency] execute error strategy={} mode={}: {}",
                    strategyId, normalizedMode, ex.getMessage(), ex);
        }
    }

    // ─── LIMIT_LADDER_CLOSE：分档 GTX ────────────────────
    /**
     * 分档挂平仓：把仓位分成 ladder_step_count 份，从 bestBid/Ask 开始每 0.05% 一档递退挂 GTX。
     * <p>
     * LONG 平仓（SELL）：[best_ask, best_ask+0.05%, best_ask+0.10%, ...]（越靠上填单越慢但价越好）
     * SHORT 平仓（BUY）：[best_bid, best_bid-0.05%, ...]
     * </p>
     */
    private void placeLadderClose(GridStrategy strategy, String positionSide,
                                   AccountCredentialResolver.Credentials creds) {
        PositionInfoResolver.PositionSummary pos = positionResolver.resolve(strategy, positionSide);
        if (pos.isEmpty()) return;
        MarketQuote quote = marketQuoteCache.get(strategy.getSymbol());
        if (quote == null) {
            log.warn("[Emergency] ladder close skipped: no quote for {}", strategy.getSymbol());
            return;
        }
        int steps = strategy.getLadderStepCount() == null ? 5 : Math.max(1, strategy.getLadderStepCount());
        BigDecimal perStep = pos.getTotalQty()
                .divide(BigDecimal.valueOf(steps), 6, RoundingMode.DOWN);
        if (perStep.compareTo(BigDecimal.ZERO) <= 0) return;

        boolean isLong = "LONG".equalsIgnoreCase(positionSide);
        String side = isLong ? "SELL" : "BUY";
        BigDecimal base = isLong ? quote.getBestAsk() : quote.getBestBid();
        BigDecimal stepPct = new BigDecimal("0.0005");   // 每档 0.05%

        int placed = 0;
        int failed = 0;
        for (int i = 0; i < steps; i++) {
            BigDecimal offset = stepPct.multiply(BigDecimal.valueOf(i));
            BigDecimal price = isLong
                    ? base.multiply(BigDecimal.ONE.add(offset))
                    : base.multiply(BigDecimal.ONE.subtract(offset));
            price = price.setScale(8, RoundingMode.HALF_UP);

            String cid = "mm-ladder-" + strategy.getId() + "-" + UUID.randomUUID().toString().substring(0, 8);
            try {
                MmOrderResult r = router.placeOrder(strategy, creds.apiKey(), creds.apiSecret(),
                        MmOrderRequest.builder()
                                .symbol(strategy.getSymbol())
                                .side(side)
                                .positionSide(positionSide)
                                .price(price)
                                .quantity(perStep)
                                .clientOrderId(cid)
                                .build());
                if (r.isSuccess()) placed++; else failed++;
            } catch (Exception ex) {
                failed++;
                log.warn("[Emergency] ladder place error step={}: {}", i, ex.getMessage());
            }
        }
        Map<String, Object> payload = new HashMap<>();
        payload.put("side", positionSide);
        payload.put("steps", steps);
        payload.put("perStepQty", perStep);
        payload.put("placed", placed);
        payload.put("failed", failed);
        eventLogger.warn(strategy.getId(), "EMERGENCY_LADDER_PLACED", payload);
    }

    // ─── MARKET_FALLBACK：直接市价单 ────────────────────
    /**
     * 直接下市价单强平。用户明确选择此模式即视为已授权 taker（会收手续费！）。
     */
    private void marketFallback(GridStrategy strategy, String positionSide,
                                 AccountCredentialResolver.Credentials creds) {
        PositionInfoResolver.PositionSummary pos = positionResolver.resolve(strategy, positionSide);
        if (pos.isEmpty()) return;
        String side = "LONG".equalsIgnoreCase(positionSide) ? "SELL" : "BUY";
        String cid = "mm-emerg-mkt-" + strategy.getId() + "-" + UUID.randomUUID().toString().substring(0, 8);
        try {
            MmOrderResult r = router.placeMarketOrder(strategy, creds.apiKey(), creds.apiSecret(),
                    strategy.getSymbol(), side, positionSide, pos.getTotalQty(), cid);
            Map<String, Object> payload = new HashMap<>();
            payload.put("side", positionSide);
            payload.put("qty", pos.getTotalQty());
            payload.put("success", r.isSuccess());
            payload.put("filled", r.getExecutedQty());
            payload.put("errorMessage", r.getErrorMessage());
            eventLogger.error(strategy.getId(), "EMERGENCY_MARKET_FALLBACK", payload);
        } catch (Exception ex) {
            log.error("[Emergency] MARKET fallback error strategy={} side={}: {}",
                    strategy.getId(), positionSide, ex.getMessage(), ex);
        }
    }
}

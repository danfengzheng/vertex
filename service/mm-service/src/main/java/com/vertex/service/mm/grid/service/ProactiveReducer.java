package com.vertex.service.mm.grid.service;

import com.vertex.service.mm.binance.MmOrderRequest;
import com.vertex.service.mm.binance.MmOrderResult;
import com.vertex.service.mm.grid.entity.GridStrategy;
import com.vertex.service.mm.market.MarketQuote;
import com.vertex.service.mm.market.MarketQuoteCache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Layer 3 主动减仓器 —— 认小亏保命。
 * <p>
 * 触发条件（由 GridEngine / 定时任务判定）：<br>
 * 1h ADX > 30 <b>且</b> 该侧浮亏 > 1% <b>且</b> 60 分钟内梯队没触发平仓
 * </p>
 * <p>
 * 动作：
 * <ol>
 *   <li>GTX 挂"贴价"平仓单（LONG 挂 SELL @ best_bid；SHORT 挂 BUY @ best_ask）</li>
 *   <li>5 秒追一次价（跟随 bid/ask 移动）</li>
 *   <li>2 分钟不成 → 降 0.05% 挂新价</li>
 *   <li>浮亏冲到 escape hatch 阈值 → 破例 taker 强平（如果 emergencyTakerFallbackEnabled=1）</li>
 * </ol>
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProactiveReducer {

    private static final int MAX_ATTEMPTS = 24;   // 5s × 24 = 2 分钟

    private final OrderClientRouter router;
    private final MarketQuoteCache quoteCache;
    private final PositionInfoResolver positionResolver;
    private final AccountCredentialResolver credentialResolver;
    private final GridEventLogger eventLogger;

    /** strategyId+side → 当前进行中的减仓上下文（防重复触发）*/
    private final ConcurrentMap<String, ReduceContext> inFlight = new ConcurrentHashMap<>();

    /**
     * 触发某侧减仓。异步执行（用调用方线程；建议 GridEngine 提供 executor 或此处新起）。
     *
     * @param strategy      策略
     * @param positionSide  LONG / SHORT
     * @param reducePct     减仓百分比（默认 30，即减 30%）
     * @return 是否触发成功（false = 已在进行 / 无仓位）
     */
    public boolean reduce(GridStrategy strategy, String positionSide, BigDecimal reducePct) {
        String key = strategy.getId() + ":" + positionSide;
        if (inFlight.containsKey(key)) {
            log.info("[ProactiveReducer] strategy={} side={} already in-flight, skip", strategy.getId(), positionSide);
            return false;
        }

        PositionInfoResolver.PositionSummary pos = positionResolver.resolve(strategy, positionSide);
        if (pos.isEmpty()) return false;

        BigDecimal reduceQty = pos.getTotalQty()
                .multiply(reducePct.divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP))
                .setScale(6, RoundingMode.DOWN);

        if (reduceQty.compareTo(BigDecimal.ZERO) <= 0) return false;

        ReduceContext ctx = new ReduceContext(strategy, positionSide, reduceQty, pos.getAvgCost());
        inFlight.put(key, ctx);

        Map<String, Object> startPayload = new HashMap<>();
        startPayload.put("side", positionSide);
        startPayload.put("qty", reduceQty);
        startPayload.put("avgCost", pos.getAvgCost());
        startPayload.put("mark", pos.getMarkPrice());
        eventLogger.warn(strategy.getId(), "LAYER3_REDUCE_START", startPayload);

        // 同步串行执行 —— 生产环境应该改用独立线程池
        try {
            runReduceLoop(ctx);
        } finally {
            inFlight.remove(key);
        }
        return true;
    }

    private void runReduceLoop(ReduceContext ctx) {
        var creds = credentialResolver.resolveOrPaper(ctx.strategy.getAccountId(),
                "PAPER".equalsIgnoreCase(ctx.strategy.getExecutionMode()));
        int attempt = 0;
        BigDecimal remaining = ctx.qty;
        String lastClientId = null;
        Long lastExchangeOrderId = null;

        while (attempt < MAX_ATTEMPTS && remaining.compareTo(BigDecimal.ZERO) > 0) {
            attempt++;
            MarketQuote quote = quoteCache.get(ctx.strategy.getSymbol());
            if (quote == null) {
                log.warn("[ProactiveReducer] strategy={} no quote, waiting", ctx.strategy.getId());
                sleep(2000);
                continue;
            }

            // 撤上一次未成交的挂单（路由到 Paper/Live）
            if (lastExchangeOrderId != null) {
                router.cancelOrder(ctx.strategy, creds.apiKey(), creds.apiSecret(),
                        ctx.strategy.getSymbol(), lastExchangeOrderId);
                lastExchangeOrderId = null;
            }

            // 计算贴价（每 5 次尝试降 0.05%）
            BigDecimal price;
            String side;
            boolean isLong = "LONG".equalsIgnoreCase(ctx.positionSide);
            if (isLong) {
                price = quote.getBestBid();
                if (attempt > 5) {
                    BigDecimal drop = new BigDecimal("0.0005").multiply(BigDecimal.valueOf((attempt - 5) / 5 + 1));
                    price = price.multiply(BigDecimal.ONE.subtract(drop));
                }
                side = "SELL";
            } else {
                price = quote.getBestAsk();
                if (attempt > 5) {
                    BigDecimal add = new BigDecimal("0.0005").multiply(BigDecimal.valueOf((attempt - 5) / 5 + 1));
                    price = price.multiply(BigDecimal.ONE.add(add));
                }
                side = "BUY";
            }
            price = price.setScale(8, RoundingMode.HALF_UP);

            String cid = "mm-reduce-" + ctx.strategy.getId() + "-" + UUID.randomUUID().toString().substring(0, 8);
            MmOrderResult r = router.placeOrder(ctx.strategy, creds.apiKey(), creds.apiSecret(),
                    MmOrderRequest.builder()
                            .symbol(ctx.strategy.getSymbol())
                            .side(side)
                            .positionSide(ctx.positionSide)
                            .price(price)
                            .quantity(remaining)
                            .clientOrderId(cid)
                            .build());

            if (!r.isSuccess()) {
                log.info("[ProactiveReducer] strategy={} attempt={} place fail: {} (gtx={})",
                        ctx.strategy.getId(), attempt, r.getErrorMessage(), r.isGtxRejected());
                sleep(1000);
                continue;
            }

            lastClientId = r.getClientOrderId();
            lastExchangeOrderId = r.getExchangeOrderId();

            // 等 5 秒看是否成交（简化：不去查订单状态，假设 executed 部分不显著）
            sleep(5000);
            // 生产版应在这里通过 UserDataStream 的成交事件或 REST 查订单确认 filled_qty
            // 此骨架只做机制演示：撤单 → 重挂
        }

        // Escape hatch: 已尽力但仍未平完 —— 若配置允许则破例 taker（真实市价单）
        if (remaining.compareTo(BigDecimal.ZERO) > 0
                && ctx.strategy.getEmergencyTakerFallbackEnabled() != null
                && ctx.strategy.getEmergencyTakerFallbackEnabled() == 1) {
            log.warn("[ProactiveReducer] strategy={} escape hatch: after {} attempts remaining {}, invoking MARKET fallback",
                    ctx.strategy.getId(), attempt, remaining);
            String cid = "mm-reduce-mkt-" + ctx.strategy.getId() + "-" + UUID.randomUUID().toString().substring(0, 8);
            String side = "LONG".equalsIgnoreCase(ctx.positionSide) ? "SELL" : "BUY";
            MmOrderResult r = router.placeMarketOrder(ctx.strategy, creds.apiKey(), creds.apiSecret(),
                    ctx.strategy.getSymbol(), side, ctx.positionSide, remaining, cid);
            Map<String, Object> takerPayload = new HashMap<>();
            takerPayload.put("remaining", remaining);
            takerPayload.put("attempts", attempt);
            takerPayload.put("success", r.isSuccess());
            takerPayload.put("errorMessage", r.getErrorMessage());
            takerPayload.put("filled", r.getExecutedQty());
            eventLogger.error(ctx.strategy.getId(), "LAYER3_TAKER_FALLBACK", takerPayload);
        }

        Map<String, Object> endPayload = new HashMap<>();
        endPayload.put("side", ctx.positionSide);
        endPayload.put("attempts", attempt);
        endPayload.put("remaining", remaining);
        eventLogger.info(ctx.strategy.getId(), "LAYER3_REDUCE_END", endPayload);
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private static class ReduceContext {
        final GridStrategy strategy;
        final String positionSide;
        final BigDecimal qty;
        final BigDecimal avgCost;
        ReduceContext(GridStrategy s, String side, BigDecimal qty, BigDecimal avgCost) {
            this.strategy = s; this.positionSide = side; this.qty = qty; this.avgCost = avgCost;
        }
    }
}

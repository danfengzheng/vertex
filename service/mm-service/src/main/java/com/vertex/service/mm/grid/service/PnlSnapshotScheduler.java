package com.vertex.service.mm.grid.service;

import com.vertex.service.mm.grid.entity.GridPnlSnapshot;
import com.vertex.service.mm.grid.entity.GridStrategy;
import com.vertex.service.mm.grid.mapper.GridPnlSnapshotMapper;
import com.vertex.service.mm.market.MarketQuote;
import com.vertex.service.mm.market.MarketQuoteCache;
import com.vertex.service.mm.risk.DepletionState;
import com.vertex.service.mm.risk.TrendScoreEvaluator;
import com.vertex.service.mm.risk.WindowDepletionAnalyzer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 每分钟采样 grid_pnl_snapshot —— 前端画曲线 + 长期分析用。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "vertex.mm", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class PnlSnapshotScheduler {

    private final GridStrategyService strategyService;
    private final GridPnlSnapshotMapper snapshotMapper;
    private final PositionInfoResolver positionResolver;
    private final SlidingWindowManager slidingWindow;
    private final WindowDepletionAnalyzer depletionAnalyzer;
    private final TrendScoreEvaluator scoreEvaluator;
    private final MarketQuoteCache quoteCache;
    private final AccountCredentialResolver credentialResolver;
    private final com.vertex.service.mm.binance.BinanceAccountClient binanceAccountClient;
    private final com.vertex.service.mm.grid.mapper.GridStrategyMapper strategyMapper;

    /** 每策略累计 fundingPnl（避免每分钟拉 24h 数据）—— key = strategyId */
    private final java.util.concurrent.ConcurrentMap<Long, BigDecimal> fundingPnlCache =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** 每策略下次拉 funding 的时间 —— 15min 一次，PAPER 不拉 */
    private final java.util.concurrent.ConcurrentMap<Long, Long> nextFundingFetchAt =
            new java.util.concurrent.ConcurrentHashMap<>();

    @Scheduled(fixedDelay = 60_000L, initialDelay = 90_000L)
    public void snapshotAll() {
        List<GridStrategy> running = strategyService.listRunning();
        for (GridStrategy s : running) {
            try {
                snapshotOne(s);
                syncStateToDb(s);   // 内存 runtime 值批量 sync（用于崩溃恢复）
            } catch (Exception ex) {
                log.warn("[PnlSnapshot] strategy={} error: {}", s.getId(), ex.getMessage());
            }
        }
    }

    /**
     * 把 GridState 内存里的实时值（center/step/longQty/shortQty/currentActiveLayer）1min sync 一次到 DB。
     * <p>
     * 目的仅为崩溃恢复：GridRecoveryService 从 DB 拿 centerPrice/stepPct 重建 slot 拓扑。
     * UI 走 /status 端点直接读内存，不依赖 DB 快照。
     * </p>
     */
    private void syncStateToDb(GridStrategy strategy) {
        var state = slidingWindow.getState(strategy.getId());
        if (state == null) return;
        try {
            com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<GridStrategy> w =
                    new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<>();
            w.eq(GridStrategy::getId, strategy.getId())
             .set(GridStrategy::getCenterPrice, state.getCenterPrice())
             .set(GridStrategy::getStepPct, state.getStepPct())
             .set(GridStrategy::getCurrentLongQty, state.getCurrentLongQty())
             .set(GridStrategy::getCurrentShortQty, state.getCurrentShortQty())
             .set(GridStrategy::getCurrentActiveLayer, state.getCurrentLayer().get());
            strategyMapper.update(null, w);
        } catch (Exception ex) {
            log.warn("[PnlSnapshot] state sync to DB failed strategy={}: {}", strategy.getId(), ex.getMessage());
        }
    }

    private void snapshotOne(GridStrategy strategy) {
        var longPos = positionResolver.resolve(strategy, "LONG");
        var shortPos = positionResolver.resolve(strategy, "SHORT");
        var state = slidingWindow.getState(strategy.getId());
        MarketQuote quote = quoteCache.get(strategy.getSymbol());

        int longActive = state == null ? 0 : state.countLongOpenOrders();
        int shortActive = state == null ? 0 : state.countShortOpenOrders();
        int windowSize = state == null ? (strategy.getWindowSizePerSide() == null ? 5
                : strategy.getWindowSizePerSide()) : state.getWindowSizePerSide();

        DepletionState depletion = depletionAnalyzer.compute(strategy.getId(), longActive, shortActive, windowSize);
        scoreEvaluator.evaluate(strategy, depletion, null);

        GridPnlSnapshot snap = new GridPnlSnapshot();
        snap.setGridStrategyId(strategy.getId());
        snap.setSnapshotAt(LocalDateTime.now());
        snap.setMarkPrice(quote == null ? null : quote.mid());
        snap.setLongQty(longPos.getTotalQty());
        snap.setShortQty(shortPos.getTotalQty());
        snap.setNetQty(longPos.getTotalQty().subtract(shortPos.getTotalQty()));
        if (quote != null) {
            snap.setNetNotional(snap.getNetQty().multiply(quote.mid()));
        }
        snap.setLongAvgCost(longPos.getAvgCost());
        snap.setShortAvgCost(shortPos.getAvgCost());
        BigDecimal unrealizedPnl = BigDecimal.ZERO;
        if (longPos.getUnrealizedPnl() != null) unrealizedPnl = unrealizedPnl.add(longPos.getUnrealizedPnl());
        if (shortPos.getUnrealizedPnl() != null) unrealizedPnl = unrealizedPnl.add(shortPos.getUnrealizedPnl());
        snap.setUnrealizedPnl(unrealizedPnl);
        snap.setRealizedArbitragePnl(strategy.getTotalArbitragePnl() != null
                ? strategy.getTotalArbitragePnl() : BigDecimal.ZERO);
        snap.setRealizedDirectionalPnl(strategy.getTotalDirectionalPnl() != null
                ? strategy.getTotalDirectionalPnl() : BigDecimal.ZERO);
        snap.setFundingPnl(resolveFundingPnl(strategy));
        snap.setFeePaid(strategy.getTotalFeePaid() != null ? strategy.getTotalFeePaid() : BigDecimal.ZERO);
        snap.setActiveOrdersCount(longActive + shortActive);
        snap.setLongDepletionPct(BigDecimal.valueOf(depletion.getLongDepletionPct()));
        snap.setShortDepletionPct(BigDecimal.valueOf(depletion.getShortDepletionPct()));
        snap.setCurrentLayer(state == null ? "NORMAL" : state.getCurrentLayer().get());
        snap.setTrendScore(depletion.getTrendScore());

        snapshotMapper.insert(snap);
    }

    /**
     * LIVE 策略：每 15min 拉 /fapi/v1/income 汇总 FUNDING_FEE。PAPER 恒为 0。
     */
    private BigDecimal resolveFundingPnl(GridStrategy strategy) {
        if ("PAPER".equalsIgnoreCase(strategy.getExecutionMode())) return BigDecimal.ZERO;

        long now = System.currentTimeMillis();
        Long next = nextFundingFetchAt.get(strategy.getId());
        if (next != null && now < next) {
            return fundingPnlCache.getOrDefault(strategy.getId(), BigDecimal.ZERO);
        }
        try {
            var creds = credentialResolver.resolveOrPaper(strategy.getAccountId(), false);
            // 从策略启动时间开始拉；无 createTime 用 24h 前兜底
            long startMs = strategy.getCreateTime() != null
                    ? strategy.getCreateTime().atZone(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
                    : now - 24L * 3600_000L;
            BigDecimal sum = binanceAccountClient.fetchFundingIncome(
                    creds.apiKey(), creds.apiSecret(), strategy.getSymbol(), startMs);
            fundingPnlCache.put(strategy.getId(), sum);
            nextFundingFetchAt.put(strategy.getId(), now + 15L * 60_000L);
            return sum;
        } catch (Exception ex) {
            log.warn("[PnlSnapshot] fundingPnl fetch error strategy={}: {}", strategy.getId(), ex.getMessage());
            return fundingPnlCache.getOrDefault(strategy.getId(), BigDecimal.ZERO);
        }
    }
}

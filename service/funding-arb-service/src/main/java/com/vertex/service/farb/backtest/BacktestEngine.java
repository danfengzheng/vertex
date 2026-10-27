package com.vertex.service.farb.backtest;

import com.vertex.service.farb.signal.FarbSignalCache;
import com.vertex.service.farb.signal.FarbSignalStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 资金费套利 - 回测引擎（v1 简化版）。
 * <p>
 * 使用 RocksDB 累积的信号数据回放：
 * <ul>
 *   <li>按 1h 步进遍历 [startTs, endTs]</li>
 *   <li>每个时点：对 universe 里每个 symbol 计算历史 funding regime → 满足条件则开新 pair</li>
 *   <li>持仓 pair：每次结算累计 funding；触发 exit 条件则平仓</li>
 *   <li>输出：equity curve + 完整指标 + 单笔明细</li>
 * </ul>
 * </p>
 * <p>
 * <b>局限性</b>：
 * <ul>
 *   <li>OI / LSR / TakerBSR / Vol：Binance 只留 30 天，早期回测这些指标可能为空 → 用 config.ignoreBasisPnl 兜底</li>
 *   <li>Basis：premiumIndexKlines 有历史，但简化版可先忽略</li>
 *   <li>Slippage / GTX reject：用固定 bps 假设，不模拟极端行情</li>
 * </ul>
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BacktestEngine {

    /** 步进：1 小时 */
    private static final long STEP_MS = TimeUnit.HOURS.toMillis(1);
    /** 平仓时的 basis 估计（忽略时用 0）*/
    private static final BigDecimal ZERO = BigDecimal.ZERO;

    private final FarbSignalStore signalStore;

    /**
     * 运行一次回测。
     */
    public BacktestResult run(BacktestConfig cfg, Set<String> universe) {
        List<String> warnings = new ArrayList<>();

        // 1) 决定 symbol 集
        Set<String> symbols = resolveSymbols(cfg, universe);
        if (symbols.isEmpty()) {
            warnings.add("EMPTY_UNIVERSE: 没有可回测的 symbol");
        }

        // 2) 预加载每 symbol 的 funding 历史（含真实 ts，Sprint 8 修复）
        Map<String, List<FundingPoint>> fundingBySymbol = new HashMap<>();
        int loadedTotal = 0;
        for (String sym : symbols) {
            var tsVals = signalStore.scanByRangeWithTs(
                    FarbSignalStore.PFX_FUNDING, sym,
                    cfg.getStartTimeMs(), cfg.getEndTimeMs(),
                    FarbSignalCache.FundingRecord.class);
            if (tsVals.isEmpty()) continue;
            List<FundingPoint> pts = new ArrayList<>(tsVals.size());
            for (var tv : tsVals) {
                pts.add(new FundingPoint(tv.ts(), tv.value().rate(), tv.value().intervalHours()));
            }
            // 保证严格按 ts 升序（RocksDB 前缀 range 按字典序 = 时间序，已有序）
            fundingBySymbol.put(sym, pts);
            loadedTotal += pts.size();
        }
        log.info("[Backtest] loaded {} funding points across {} symbols", loadedTotal, fundingBySymbol.size());
        if (loadedTotal == 0) {
            warnings.add("NO_FUNDING_DATA: RocksDB 没有回测区间内的 funding 数据，请先跑 DataCollector 积累");
        }

        // 3) 回测主循环
        BigDecimal equity = cfg.getInitialCapital();
        BigDecimal equityHigh = equity;
        BigDecimal maxDrawdown = ZERO;
        List<BacktestResult.EquityPoint> curve = new ArrayList<>();
        curve.add(BacktestResult.EquityPoint.builder()
                .ts(cfg.getStartTimeMs()).equity(equity).drawdownPct(ZERO).build());

        List<VirtualPair> openPairs = new ArrayList<>();
        List<BacktestResult.PairTrade> closedTrades = new ArrayList<>();
        Map<String, Integer> triggerDist = new HashMap<>();
        Map<String, SymbolStats> symbolStats = new HashMap<>();
        /** symbol → 允许下一次入场的最早 ts（冷静期结束时刻）*/
        Map<String, Long> symbolCooldownUntil = new HashMap<>();
        List<BigDecimal> dailyReturns = new ArrayList<>();
        BigDecimal dayStartEquity = equity;
        long dayBoundary = cfg.getStartTimeMs() + 86_400_000L;

        for (long t = cfg.getStartTimeMs(); t < cfg.getEndTimeMs(); t += STEP_MS) {
            final long tick = t;   // lambda 引用需 effectively final
            // 处理 exit
            openPairs.removeIf(p -> {
                String trigger = evaluateExit(p, cfg, fundingBySymbol.get(p.symbol), tick);
                if (trigger == null) return false;
                closePair(p, cfg, tick, trigger, closedTrades, triggerDist, symbolStats);
                // 冷静期：出场后同一 symbol 在指定小时内不允许再入场，避免白吃 fee
                int cooldownH = switch (trigger) {
                    case "REGIME_BREAK"      -> cfg.getCooldownRegimeBreakH();
                    case "TAKE_PROFIT"       -> cfg.getCooldownTakeProfitH();
                    case "MAX_HOLD_TIMEOUT"  -> cfg.getCooldownMaxHoldH();
                    case "BASIS_BREACH", "BASIS_EMERGENCY" -> cfg.getCooldownBasisBreachH();
                    default                    -> 0;
                };
                if (cooldownH > 0) {
                    symbolCooldownUntil.put(p.symbol, tick + cooldownH * 3600_000L);
                }
                return true;
            });

            // 更新持仓的 funding accrual（在结算时点）
            for (VirtualPair p : openPairs) {
                accrueFunding(p, fundingBySymbol.get(p.symbol), t, cfg);
            }

            // 尝试开新 pair
            int slotsFree = cfg.getMaxOpenPairs() - openPairs.size();
            if (slotsFree > 0) {
                Map<String, Integer> perSymbol = new HashMap<>();
                for (VirtualPair p : openPairs) perSymbol.merge(p.symbol, 1, Integer::sum);
                List<VirtualPair> newOpens = tryOpenNewPairs(cfg, fundingBySymbol, symbols, t,
                        slotsFree, perSymbol);
                for (VirtualPair np : newOpens) {
                    // 扣入场手续费
                    BigDecimal entryFee = cfg.getNotionalPerPair()
                            .multiply(cfg.getSpotFeeRate().add(cfg.getPerpFeeRate()));
                    np.feePaid = entryFee;
                    openPairs.add(np);
                }
            }

            // equity 快照：等于 initial + 已实现 pnl + 未实现 funding + 未扣入场 fee
            BigDecimal realized = ZERO;
            for (var trade : closedTrades) realized = realized.add(trade.getNetPnl());
            BigDecimal unrealizedFunding = ZERO;
            BigDecimal unrealizedFee = ZERO;
            for (VirtualPair p : openPairs) {
                unrealizedFunding = unrealizedFunding.add(p.fundingAccrued);
                unrealizedFee = unrealizedFee.add(p.feePaid);
            }
            equity = cfg.getInitialCapital().add(realized).add(unrealizedFunding).subtract(unrealizedFee);

            if (equity.compareTo(equityHigh) > 0) equityHigh = equity;
            BigDecimal drawdown = equityHigh.compareTo(ZERO) == 0 ? ZERO
                    : equityHigh.subtract(equity).divide(equityHigh, 6, RoundingMode.HALF_UP)
                            .multiply(BigDecimal.valueOf(100));
            if (drawdown.compareTo(maxDrawdown) > 0) maxDrawdown = drawdown;

            // 每 6h 一个 equity 点，避免曲线太密
            if ((t - cfg.getStartTimeMs()) % (6 * STEP_MS) == 0) {
                curve.add(BacktestResult.EquityPoint.builder()
                        .ts(t).equity(equity).drawdownPct(drawdown).build());
            }

            // 每日 return
            if (t >= dayBoundary) {
                BigDecimal ret = dayStartEquity.compareTo(ZERO) == 0 ? ZERO
                        : equity.subtract(dayStartEquity).divide(dayStartEquity, 8, RoundingMode.HALF_UP);
                dailyReturns.add(ret);
                dayStartEquity = equity;
                dayBoundary += 86_400_000L;
            }
        }

        // 收尾：所有 open pair 强制平仓
        for (VirtualPair p : new ArrayList<>(openPairs)) {
            closePair(p, cfg, cfg.getEndTimeMs(), "END_OF_BACKTEST", closedTrades, triggerDist, symbolStats);
        }
        openPairs.clear();

        BigDecimal finalEquity = cfg.getInitialCapital();
        for (var trade : closedTrades) finalEquity = finalEquity.add(trade.getNetPnl());

        return buildResult(cfg, symbols.size(), finalEquity, maxDrawdown, curve,
                closedTrades, triggerDist, symbolStats, dailyReturns, warnings);
    }

    // ─── 内部：策略主循环 helpers ────────────────────────────────────────

    private Set<String> resolveSymbols(BacktestConfig cfg, Set<String> universe) {
        Set<String> result = new HashSet<>();
        if (cfg.getSymbolWhitelist() != null && !cfg.getSymbolWhitelist().isEmpty()) {
            result.addAll(cfg.getSymbolWhitelist());
        } else {
            result.addAll(universe);
        }
        if (cfg.getSymbolBlacklist() != null) result.removeAll(cfg.getSymbolBlacklist());
        return result;
    }

    /**
     * 决定当前时点是否为该 symbol 开新 pair。仅使用 funding 历史（v1 简化）。
     */
    private List<VirtualPair> tryOpenNewPairs(BacktestConfig cfg,
                                              Map<String, List<FundingPoint>> fundingBySymbol,
                                              Set<String> symbols, long ts, int slotsFree,
                                              Map<String, Integer> perSymbol) {
        // 每个 symbol 计算 regime 得分 → 按 avg funding 降序取前 K
        List<Candidate> qualified = new ArrayList<>();
        for (String sym : symbols) {
            if (perSymbol.getOrDefault(sym, 0) >= cfg.getMaxPairsPerSymbol()) continue;
            List<FundingPoint> hist = fundingBySymbol.get(sym);
            if (hist == null || hist.isEmpty()) continue;
            // 找到 ts 之前最近的 lookback 次 funding
            int endIdx = -1;
            for (int i = 0; i < hist.size(); i++) {
                if (hist.get(i).ts <= ts) endIdx = i;
                else break;
            }
            int startIdx = endIdx - cfg.getLookbackSettlements() + 1;
            if (startIdx < 0 || endIdx < 0) continue;
            double sumBps = 0;
            int hits = 0;
            double latestBps = 0;
            for (int i = startIdx; i <= endIdx; i++) {
                var fp = hist.get(i);
                double norm = fp.rate * (8.0 / fp.intervalHours) * 10000.0;
                sumBps += norm;
                if (norm >= cfg.getMinAvgFundingBps().doubleValue()) hits++;
                if (i == endIdx) latestBps = norm;
            }
            double avg = sumBps / cfg.getLookbackSettlements();
            double hitRatio = (double) hits / cfg.getLookbackSettlements();

            // 只做正 funding
            if (avg <= 0) continue;
            if (avg < cfg.getMinAvgFundingBps().doubleValue()) continue;
            if (hitRatio < cfg.getMinHitRatio().doubleValue()) continue;
            if (latestBps < cfg.getMinLatestFundingBps().doubleValue()) continue;

            qualified.add(new Candidate(sym, avg, hitRatio, latestBps));
        }
        qualified.sort(Comparator.comparingDouble((Candidate c) -> c.avg).reversed());

        List<VirtualPair> opens = new ArrayList<>();
        int take = Math.min(slotsFree, qualified.size());
        for (int i = 0; i < take; i++) {
            var c = qualified.get(i);
            VirtualPair p = new VirtualPair();
            p.symbol = c.symbol;
            p.entryTs = ts;
            // 关键：只累计 entryTs 之后的 funding（不能白拿入场瞬间那笔已发生的结算）
            p.lastAccrualTs = ts;
            p.entryAvgFundingBps = c.avg;
            p.notional = cfg.getNotionalPerPair();
            opens.add(p);
        }
        return opens;
    }

    /** 在时点 t 处如果达到结算时点，累加 funding 到 pair.fundingAccrued */
    private void accrueFunding(VirtualPair p, List<FundingPoint> hist, long t, BacktestConfig cfg) {
        if (hist == null) return;
        // 找到 (p.lastAccrualTs, t] 之间的所有 funding 结算
        for (FundingPoint fp : hist) {
            if (fp.ts <= p.lastAccrualTs) continue;
            if (fp.ts > t) break;
            // 累加 funding_amount = notional × rate（简化：直接按名义计算）
            BigDecimal amt = p.notional.multiply(BigDecimal.valueOf(fp.rate));
            p.fundingAccrued = p.fundingAccrued.add(amt);
            p.settlementCount++;
            p.lastAccrualTs = fp.ts;
        }
    }

    /**
     * 检查是否触发出场。返回 trigger 名称或 null。
     */
    private String evaluateExit(VirtualPair p, BacktestConfig cfg, List<FundingPoint> hist, long t) {
        // TAKE_PROFIT: net_pnl / notional > tp （<=0 = 禁用，让 funding regime 自然出场）
        if (cfg.getExitTakeProfitPct() != null && cfg.getExitTakeProfitPct().compareTo(ZERO) > 0) {
            BigDecimal estFee = cfg.getNotionalPerPair()
                    .multiply(cfg.getSpotFeeRate().add(cfg.getPerpFeeRate()))
                    .multiply(BigDecimal.valueOf(2));   // 入场 + 出场
            BigDecimal netUnrealized = p.fundingAccrued.subtract(estFee);
            BigDecimal netPct = cfg.getNotionalPerPair().compareTo(ZERO) == 0 ? ZERO
                    : netUnrealized.divide(cfg.getNotionalPerPair(), 6, RoundingMode.HALF_UP)
                            .multiply(BigDecimal.valueOf(100));
            if (netPct.compareTo(cfg.getExitTakeProfitPct()) >= 0) return "TAKE_PROFIT";
        }

        // MAX_HOLD
        long holdHours = (t - p.entryTs) / 3600_000L;
        if (holdHours >= cfg.getExitMaxHoldHours()) return "MAX_HOLD_TIMEOUT";

        // REGIME_BREAK: 最近 break_count 次 funding 都 < min
        if (hist != null) {
            int consecutiveBreak = 0;
            for (int i = hist.size() - 1; i >= 0; i--) {
                var fp = hist.get(i);
                if (fp.ts > t) continue;
                double norm = fp.rate * (8.0 / fp.intervalHours) * 10000.0;
                if (norm < cfg.getMinAvgFundingBps().doubleValue()) {
                    consecutiveBreak++;
                } else {
                    break;
                }
                if (consecutiveBreak >= cfg.getExitRegimeBreakCount()) return "REGIME_BREAK";
            }
        }
        return null;
    }

    private void closePair(VirtualPair p, BacktestConfig cfg, long exitTs, String trigger,
                            List<BacktestResult.PairTrade> closedTrades,
                            Map<String, Integer> triggerDist,
                            Map<String, SymbolStats> symbolStats) {
        BigDecimal exitFee = cfg.getNotionalPerPair()
                .multiply(cfg.getSpotFeeRate().add(cfg.getPerpFeeRate()));
        BigDecimal totalFee = p.feePaid.add(exitFee);
        // 滑点：每腿一次 × 2 腿 × 2 次 = 4 次
        BigDecimal slippage = cfg.getNotionalPerPair()
                .multiply(cfg.getSlippagePerLegBps().divide(BigDecimal.valueOf(10000)))
                .multiply(BigDecimal.valueOf(4));
        BigDecimal netPnl = p.fundingAccrued.subtract(totalFee).subtract(slippage);
        BigDecimal basisPnl = ZERO;   // v1 忽略

        int holdHours = (int) ((exitTs - p.entryTs) / 3600_000L);
        var trade = BacktestResult.PairTrade.builder()
                .symbol(p.symbol)
                .entryTs(p.entryTs)
                .exitTs(exitTs)
                .holdHours(holdHours)
                .settlementCount(p.settlementCount)
                .fundingPnl(p.fundingAccrued)
                .basisPnl(basisPnl)
                .feePaid(totalFee.add(slippage))
                .netPnl(netPnl)
                .closeTrigger(trigger)
                .build();
        closedTrades.add(trade);
        triggerDist.merge(trigger, 1, Integer::sum);

        SymbolStats stats = symbolStats.computeIfAbsent(p.symbol, s -> new SymbolStats());
        stats.count++;
        stats.totalPnl = stats.totalPnl.add(netPnl);
        if (netPnl.compareTo(ZERO) > 0) stats.wins++;
    }

    // ─── 结果构建 ────────────────────────────────────────────────────────

    private BacktestResult buildResult(BacktestConfig cfg, int universeSize,
                                        BigDecimal finalEquity, BigDecimal maxDrawdown,
                                        List<BacktestResult.EquityPoint> curve,
                                        List<BacktestResult.PairTrade> closedTrades,
                                        Map<String, Integer> triggerDist,
                                        Map<String, SymbolStats> symbolStats,
                                        List<BigDecimal> dailyReturns,
                                        List<String> warnings) {
        BigDecimal totalFunding = ZERO;
        BigDecimal totalBasis = ZERO;
        BigDecimal totalFee = ZERO;
        int wins = 0;
        BigDecimal totalHold = ZERO;
        int totalSettlements = 0;
        for (var t : closedTrades) {
            totalFunding = totalFunding.add(t.getFundingPnl());
            totalBasis = totalBasis.add(t.getBasisPnl());
            totalFee = totalFee.add(t.getFeePaid());
            if (t.getNetPnl().compareTo(ZERO) > 0) wins++;
            totalHold = totalHold.add(BigDecimal.valueOf(t.getHoldHours()));
            totalSettlements += t.getSettlementCount();
        }
        int n = closedTrades.size();

        BigDecimal totalNet = finalEquity.subtract(cfg.getInitialCapital());
        BigDecimal totalReturn = cfg.getInitialCapital().compareTo(ZERO) == 0 ? ZERO
                : totalNet.divide(cfg.getInitialCapital(), 6, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100));

        long durationMs = cfg.getEndTimeMs() - cfg.getStartTimeMs();
        double years = durationMs / (365.0 * 86_400_000L);
        BigDecimal annualized = years > 0.001
                ? totalReturn.divide(BigDecimal.valueOf(years), 4, RoundingMode.HALF_UP)
                : ZERO;

        BigDecimal sharpe = computeSharpe(dailyReturns);

        // Top / bottom symbols
        List<BacktestResult.SymbolPerf> symbolsPerfList = symbolStats.entrySet().stream()
                .map(e -> BacktestResult.SymbolPerf.builder()
                        .symbol(e.getKey())
                        .pairCount(e.getValue().count)
                        .totalNetPnl(e.getValue().totalPnl)
                        .winRatePct(e.getValue().count == 0 ? ZERO
                                : BigDecimal.valueOf(e.getValue().wins * 100.0 / e.getValue().count)
                                        .setScale(1, RoundingMode.HALF_UP))
                        .avgNetPnl(e.getValue().count == 0 ? ZERO
                                : e.getValue().totalPnl.divide(BigDecimal.valueOf(e.getValue().count),
                                        4, RoundingMode.HALF_UP))
                        .build())
                .sorted(Comparator.comparing(BacktestResult.SymbolPerf::getTotalNetPnl).reversed())
                .toList();
        List<BacktestResult.SymbolPerf> top = symbolsPerfList.stream().limit(10).toList();
        List<BacktestResult.SymbolPerf> bottom = symbolsPerfList.size() > 10
                ? symbolsPerfList.subList(Math.max(0, symbolsPerfList.size() - 10), symbolsPerfList.size())
                : Collections.emptyList();

        // 只留 500 条最近的 trade
        int start = Math.max(0, closedTrades.size() - 500);
        List<BacktestResult.PairTrade> tradesTrimmed = closedTrades.subList(start, closedTrades.size());

        return BacktestResult.builder()
                .config(cfg)
                .startTimeMs(cfg.getStartTimeMs())
                .endTimeMs(cfg.getEndTimeMs())
                .universeSize(universeSize)
                .initialCapital(cfg.getInitialCapital())
                .finalCapital(finalEquity)
                .totalNetPnl(totalNet)
                .totalFundingPnl(totalFunding)
                .totalBasisPnl(totalBasis)
                .totalFeePaid(totalFee)
                .totalReturnPct(totalReturn)
                .annualizedReturnPct(annualized)
                .sharpe(sharpe)
                .maxDrawdownPct(maxDrawdown)
                .totalPairsOpened(closedTrades.size())
                .totalPairsClosed(closedTrades.size())
                .winCount(wins)
                .lossCount(n - wins)
                .winRatePct(n == 0 ? ZERO
                        : BigDecimal.valueOf(wins * 100.0 / n).setScale(1, RoundingMode.HALF_UP))
                .avgPairNetPnl(n == 0 ? ZERO
                        : totalNet.divide(BigDecimal.valueOf(n), 4, RoundingMode.HALF_UP))
                .avgHoldingHours(n == 0 ? ZERO
                        : totalHold.divide(BigDecimal.valueOf(n), 1, RoundingMode.HALF_UP))
                .avgSettlementsPerPair(n == 0 ? ZERO
                        : BigDecimal.valueOf(totalSettlements / (double) n).setScale(2, RoundingMode.HALF_UP))
                .closeTriggerDistribution(triggerDist)
                .topSymbols(top)
                .bottomSymbols(bottom)
                .equityCurve(curve)
                .trades(tradesTrimmed)
                .warnings(warnings)
                .build();
    }

    private BigDecimal computeSharpe(List<BigDecimal> dailyReturns) {
        if (dailyReturns.size() < 2) return ZERO;
        double sum = 0;
        for (var r : dailyReturns) sum += r.doubleValue();
        double mean = sum / dailyReturns.size();
        double var = 0;
        for (var r : dailyReturns) var += Math.pow(r.doubleValue() - mean, 2);
        double std = Math.sqrt(var / (dailyReturns.size() - 1));
        if (std < 1e-9) return ZERO;
        double sharpe = (mean / std) * Math.sqrt(365);
        return BigDecimal.valueOf(sharpe).setScale(2, RoundingMode.HALF_UP);
    }

    // ─── 内部数据结构 ────────────────────────────────────────────────────

    private static class VirtualPair {
        String symbol;
        long entryTs;
        long lastAccrualTs = 0;
        double entryAvgFundingBps;
        BigDecimal notional = ZERO;
        BigDecimal fundingAccrued = ZERO;
        BigDecimal feePaid = ZERO;
        int settlementCount = 0;
    }

    private record FundingPoint(long ts, double rate, int intervalHours) {}
    private record Candidate(String symbol, double avg, double hitRatio, double latest) {}

    private static class SymbolStats {
        int count = 0;
        int wins = 0;
        BigDecimal totalPnl = ZERO;
    }
}

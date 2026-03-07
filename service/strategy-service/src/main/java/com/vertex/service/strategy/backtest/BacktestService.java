package com.vertex.service.strategy.backtest;

import com.alibaba.fastjson2.JSON;
import com.vertex.common.core.GlobalError;
import com.vertex.common.core.exception.BizException;
import com.vertex.model.dto.strategy.BacktestConfigDTO;
import com.vertex.model.dto.strategy.StrategyIndicatorConfig;
import com.vertex.model.entity.quote.KLine;
import com.vertex.model.entity.strategy.Signal;
import com.vertex.model.entity.strategy.SignalType;
import com.vertex.model.entity.strategy.Strategy;
import com.vertex.model.vo.strategy.BacktestResultVO;
import com.vertex.model.vo.strategy.BacktestResultVO.EquityPoint;
import com.vertex.model.vo.strategy.BacktestResultVO.TradeRecord;
import com.vertex.service.quote.store.KLineStore;
import com.vertex.service.strategy.engine.KLinePartialBarBuilder;
import com.vertex.service.strategy.engine.SignalGenerator;
import com.vertex.service.strategy.indicator.IndicatorRegistry;
import com.vertex.service.strategy.indicator.TechnicalIndicator;
import com.vertex.service.strategy.mapper.StrategyMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import com.vertex.model.entity.quote.KLineInterval;
import com.vertex.service.strategy.config.StrategyProperties;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 策略回测服务
 * <p>
 * 基于历史K线数据，模拟策略交易，计算收益指标。
 * 支持多周期指标，与 StrategyEngineService 信号逻辑一致。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BacktestService {

    private final StrategyMapper strategyMapper;
    private final KLineStore klineStore;
    private final IndicatorRegistry indicatorRegistry;
    private final SignalGenerator signalGenerator;
    private final StrategyProperties properties;

    /**
     * 执行策略回测
     */
    public BacktestResultVO runBacktest(BacktestConfigDTO config) {
        // 1. 加载策略
        Strategy strategy = strategyMapper.selectById(config.getStrategyId());
        if (strategy == null) {
            throw new BizException(GlobalError.STRATEGY_NOT_FOUND);
        }

        List<StrategyIndicatorConfig> indicatorConfigs = JSON.parseArray(
                strategy.getIndicatorConfigs(), StrategyIndicatorConfig.class);
        if (indicatorConfigs == null || indicatorConfigs.isEmpty()) {
            throw new BizException(GlobalError.STRATEGY_CONFIG_ERROR);
        }

        // 2. 按周期计算所需数据量，加载多周期K线（与 StrategyEngineService 一致）
        Map<KLineInterval, Integer> requiredByInterval = new HashMap<>();
        Set<KLineInterval> allIntervals = new HashSet<>();
        allIntervals.add(strategy.getInterval());
        for (StrategyIndicatorConfig ic : indicatorConfigs) {
            KLineInterval iv = getEffectiveInterval(ic, strategy);
            allIntervals.add(iv);
            int req = indicatorRegistry.get(ic.getIndicatorType()).requiredDataPoints(ic.getParams());
            requiredByInterval.merge(iv, req, Math::max);
        }

        Map<KLineInterval, List<KLine>> allKlinesByInterval = new HashMap<>();
        int mainRequired = requiredByInterval.getOrDefault(strategy.getInterval(), 50);
        long mainIntervalMillis = strategy.getInterval().getMillis();
        int mainWarmup = properties.getEngine().getWarmupMultiplier();
        // 预取足够的预热K线，确保回测第一根K线就有 required × warmupMultiplier + 10 根历史数据
        long mainPrefetchStart = config.getStartTime() - mainIntervalMillis * (mainRequired * mainWarmup + 10);

        List<KLine> mainKlines = klineStore.query(
                strategy.getExchange(), strategy.getSymbol(), strategy.getInterval(),
                mainPrefetchStart, config.getEndTime(), Integer.MAX_VALUE, true);
        allKlinesByInterval.put(strategy.getInterval(), mainKlines);

        for (KLineInterval iv : allIntervals) {
            if (iv.equals(strategy.getInterval())) continue;
            int req = requiredByInterval.getOrDefault(iv, 50);
            long ivMillis = iv.getMillis();
            long prefetchStart = config.getStartTime() - ivMillis * (req * mainWarmup + 10);
            List<KLine> ivKlines = klineStore.query(
                    strategy.getExchange(), strategy.getSymbol(), iv,
                    prefetchStart, config.getEndTime(), Integer.MAX_VALUE, true);
            allKlinesByInterval.put(iv, ivKlines);
        }

        if (allIntervals.size() > 1) {
            log.info("Backtest [{}] multi-interval mode: {}", strategy.getName(),
                    allIntervals.stream().map(KLineInterval::getCode).sorted().toList());
        }
        log.info("Backtest [{}] loaded K-lines: {} {} (main: {} bars), required: {}",
                strategy.getName(), strategy.getExchange(), strategy.getSymbol(),
                mainKlines.size(), mainRequired + 1);

        // 最小需要 warmupSize 根预热K线 + 至少1根回测K线
        int minRequired = Math.min(mainRequired * mainWarmup + 10, properties.getEngine().getMaxKlineHistory()) + 1;
        if (mainKlines.size() < minRequired) {
            log.warn("Backtest [{}] insufficient data: got {} K-lines, need at least {} "
                    + "(required={} × warmup={} + 10 + 1). "
                    + "Please use the backfill feature to fetch historical K-line data first.",
                    strategy.getName(), mainKlines.size(), minRequired, mainRequired, mainWarmup);
            throw new BizException(GlobalError.BACKTEST_INSUFFICIENT_DATA);
        }

        // 3. 执行模拟交易
        return simulateTrades(strategy, indicatorConfigs, mainKlines, allKlinesByInterval, config,
                mainRequired, requiredByInterval);
    }

    private KLineInterval getEffectiveInterval(StrategyIndicatorConfig config, Strategy strategy) {
        return config.getInterval() != null ? config.getInterval() : strategy.getInterval();
    }

    private BacktestResultVO simulateTrades(
            Strategy strategy,
            List<StrategyIndicatorConfig> configs,
            List<KLine> klines,
            Map<KLineInterval, List<KLine>> allKlinesByInterval,
            BacktestConfigDTO config,
            int mainRequiredDataPoints,
            Map<KLineInterval, Integer> requiredByInterval) {

        int maxKlineHistory = properties.getEngine().getMaxKlineHistory();
        int warmup = properties.getEngine().getWarmupMultiplier();

        BigDecimal capital = config.getInitialCapital();
        BigDecimal position = BigDecimal.ZERO;
        BigDecimal entryPrice = BigDecimal.ZERO;
        Long entryTime = null;
        boolean inPosition = false;
        // 止损止盈触发价（开仓时按策略配置计算，null 表示未配置）
        BigDecimal stopLossPrice = null;
        BigDecimal takeProfitPrice = null;

        List<TradeRecord> trades = new ArrayList<>();
        List<EquityPoint> equityCurve = new ArrayList<>();

        BigDecimal peakEquity = capital;
        BigDecimal maxDrawdown = BigDecimal.ZERO;
        int maxDrawdownDuration = 0;
        int currentDrawdownDuration = 0;

        // 滑动窗口模拟（以策略主周期为时间轴）
        // 起始索引取 warmupSize-1，确保首次评估时就能取到完整的预热窗口
        int mainWarmupSize = Math.min(mainRequiredDataPoints * warmup + 10, maxKlineHistory);
        int startIdx = Math.max(mainRequiredDataPoints, mainWarmupSize - 1);

        // ── 次级周期单调推进指针 ────────────────────────────────────────────────────
        // currentKline.getOpenTime() 严格单调递增，已收盘/已处理的右边界只会前进，
        // 无需每轮从头全扫 ivList（O(n×m) → O(n+m)）。
        Map<KLineInterval, Integer> secPointers = new HashMap<>();
        for (KLineInterval iv : allKlinesByInterval.keySet()) {
            if (!iv.equals(strategy.getInterval())) {
                secPointers.put(iv, 0);
            }
        }

        for (int i = startIdx; i < klines.size(); i++) {
            KLine currentKline = klines.get(i);

            // 仅处理回测时间范围内的K线
            if (currentKline.getOpenTime() < config.getStartTime()) {
                continue;
            }

            // 构建多周期 K 线窗口，使用 warmupMultiplier 倍数预热，与 StrategyEngineService 一致
            Map<KLineInterval, List<KLine>> klinesByInterval = new HashMap<>();
            klinesByInterval.put(strategy.getInterval(), klines.subList(Math.max(0, i - mainWarmupSize + 1), i + 1));

            long triggerOpenTime = currentKline.getOpenTime();

            for (Map.Entry<KLineInterval, List<KLine>> e : allKlinesByInterval.entrySet()) {
                KLineInterval secInterval = e.getKey();
                if (secInterval.equals(strategy.getInterval())) continue;
                List<KLine> ivList = e.getValue();
                int req = requiredByInterval.getOrDefault(secInterval, 50);
                int fetchSize = Math.min(req * warmup + 10, maxKlineHistory);

                int ptr = secPointers.getOrDefault(secInterval, 0);

                if (secInterval.getMillis() > strategy.getInterval().getMillis()) {
                    // 高周期：推进已收盘指针（closeTime < triggerOpenTime）
                    // 消除 Lookahead Bias：不纳入尚未完整收盘的 bar
                    while (ptr < ivList.size()) {
                        KLine k = ivList.get(ptr);
                        if (k.getCloseTime() != null && k.getCloseTime() < triggerOpenTime) {
                            ptr++;
                        } else {
                            break;
                        }
                    }
                    secPointers.put(secInterval, ptr);
                    // closedWindow = 最近 fetchSize 根已收盘高周期 bar（O(1) subList 视图）
                    List<KLine> closedWindow = ivList.subList(Math.max(0, ptr - fetchSize), ptr);

                    // ── buildPartialBar 优化：O(n²) → O(log n) ───────────────────────
                    // 原实现传入 klines.subList(0, i+1)，buildPartialBar 内部每轮全扫 i+1 个元素，
                    // 造成总扫描量 1+2+…+n = O(n²)（30天1m约 9 亿次操作）。
                    // 优化：二分查找当前高周期的 periodStart 在主周期 klines 中的起始索引，
                    // 仅传入本周期内的切片（最多 secInterval/primaryInterval 根，如 1h/1m=60 根），
                    // 且切片已按 openTime 升序，buildPartialBar 无需再排序。
                    long secIntervalMs = secInterval.getMillis();
                    long periodStart = (triggerOpenTime / secIntervalMs) * secIntervalMs;
                    int lo = 0, hi = i;
                    while (lo < hi) {
                        int mid = (lo + hi) >>> 1;
                        if (klines.get(mid).getOpenTime() < periodStart) lo = mid + 1;
                        else hi = mid;
                    }
                    KLine partialBar = KLinePartialBarBuilder.buildPartialBar(
                            klines.subList(lo, i + 1),   // 仅含当前高周期内的主周期 bars，已升序
                            secInterval,
                            triggerOpenTime,
                            strategy.getExchange(),
                            strategy.getSymbol());

                    List<KLine> windowWithPartial = new ArrayList<>(closedWindow);
                    if (partialBar != null) {
                        windowWithPartial.add(partialBar);
                    }
                    if (!windowWithPartial.isEmpty()) {
                        klinesByInterval.put(secInterval, windowWithPartial);
                    }
                } else {
                    // 小周期：推进已处理指针（openTime <= triggerOpenTime）
                    while (ptr < ivList.size() && ivList.get(ptr).getOpenTime() <= triggerOpenTime) {
                        ptr++;
                    }
                    secPointers.put(secInterval, ptr);
                    // window = 最近 fetchSize 根小周期 bar（O(1) subList 视图）
                    List<KLine> window = ivList.subList(Math.max(0, ptr - fetchSize), ptr);
                    if (window.size() >= req) {
                        klinesByInterval.put(secInterval, window);
                    }
                }
            }
            Signal signal = signalGenerator.evaluate(strategy, configs, klinesByInterval);

            // 当前 bar 收盘价（用于权益计算与信号平仓）
            BigDecimal currentPrice = currentKline.getClose();

            // ── 止损止盈检查（优先于信号，使用 K 线 High/Low 判断触发）───────────────────
            // 同一根 K 线同时触及止损和止盈时，止损优先（保守原则）
            boolean closedByStopOrTp = false;
            if (inPosition) {
                boolean stopHit = stopLossPrice != null
                        && currentKline.getLow() != null
                        && currentKline.getLow().compareTo(stopLossPrice) <= 0;
                boolean tpHit = !stopHit && takeProfitPrice != null
                        && currentKline.getHigh() != null
                        && currentKline.getHigh().compareTo(takeProfitPrice) >= 0;

                if (stopHit || tpHit) {
                    BigDecimal exitPrice = stopHit ? stopLossPrice : takeProfitPrice;
                    String exitReason  = stopHit ? "STOP_LOSS" : "TAKE_PROFIT";

                    BigDecimal exitValue = position.multiply(exitPrice);
                    BigDecimal fee       = exitValue.multiply(config.getFeeRate());
                    BigDecimal netValue  = exitValue.subtract(fee);
                    BigDecimal cost      = position.multiply(entryPrice);
                    BigDecimal profit    = netValue.subtract(cost);
                    BigDecimal profitPct = cost.compareTo(BigDecimal.ZERO) > 0
                            ? profit.divide(cost, 6, RoundingMode.HALF_UP).multiply(new BigDecimal("100"))
                            : BigDecimal.ZERO;

                    trades.add(TradeRecord.builder()
                            .entryTime(entryTime)
                            .exitTime(currentKline.getOpenTime())
                            .type("LONG")
                            .entryPrice(entryPrice.setScale(8, RoundingMode.HALF_UP))
                            .exitPrice(exitPrice.setScale(8, RoundingMode.HALF_UP))
                            .quantity(position.setScale(8, RoundingMode.HALF_UP))
                            .profit(profit.setScale(2, RoundingMode.HALF_UP))
                            .profitPercent(profitPct.setScale(2, RoundingMode.HALF_UP))
                            .exitReason(exitReason)
                            .build());

                    capital        = capital.add(netValue);
                    position       = BigDecimal.ZERO;
                    inPosition     = false;
                    stopLossPrice  = null;
                    takeProfitPrice = null;
                    closedByStopOrTp = true;
                    // 止损/止盈当根 K 线权益以出场价结算
                    currentPrice = exitPrice;
                }
            }

            // 当前权益（止损/止盈出场后 inPosition=false，capital 已更新）
            BigDecimal currentEquity = inPosition
                    ? capital.add(position.multiply(currentPrice))
                    : capital;

            // 每 10 根K线记录一个资金曲线点（防止数据过多）
            if (equityCurve.isEmpty() || (i % 10 == 0)) {
                equityCurve.add(EquityPoint.builder()
                        .time(currentKline.getOpenTime())
                        .equity(currentEquity.setScale(2, RoundingMode.HALF_UP))
                        .build());
            }

            // 跟踪回撤
            if (currentEquity.compareTo(peakEquity) > 0) {
                peakEquity = currentEquity;
                currentDrawdownDuration = 0;
            } else {
                currentDrawdownDuration++;
                if (peakEquity.compareTo(BigDecimal.ZERO) > 0) {
                    BigDecimal drawdown = peakEquity.subtract(currentEquity)
                            .divide(peakEquity, 6, RoundingMode.HALF_UP)
                            .multiply(new BigDecimal("100"));
                    if (drawdown.compareTo(maxDrawdown) > 0) {
                        maxDrawdown = drawdown;
                        maxDrawdownDuration = currentDrawdownDuration;
                    }
                }
            }

            // 根据信号执行交易（止损/止盈出场后本根 K 线不再重新开仓）
            // 最低信号强度门槛：与实盘 StrategyEngineService.meetsStrengthThreshold() 保持一致
            int minStrength = (strategy.getMinSignalStrength() != null && strategy.getMinSignalStrength() > 0)
                    ? strategy.getMinSignalStrength() : 60;
            if (!inPosition && !closedByStopOrTp && signal.getSignalType() == SignalType.BUY
                    && signal.getSignalStrength() != null && signal.getSignalStrength() >= minStrength) {
                // 开仓
                // ── ATR 仓位控制系数 ──────────────────────────────────────────────
                // 策略配置了 atrStopMultiplier 时，用 ATR14/ATR50 比值动态缩放头寸：
                //   coefficient = ATR50 / ATR14，当前波动率 > 历史均值时 < 1.0 → 减仓
                //   coefficient 钳制在 [0.2, 1.0]，避免极端行情下仓位过小
                // 未配置 atrStopMultiplier 时按 positionRatio 全量开仓
                BigDecimal baseTradeAmount = capital.multiply(config.getPositionRatio());
                BigDecimal tradeAmount = baseTradeAmount;
                boolean hasAtrPositionSizing = strategy.getAtrStopMultiplier() != null
                        && strategy.getAtrStopMultiplier().compareTo(BigDecimal.ZERO) > 0;
                if (hasAtrPositionSizing) {
                    BigDecimal atr14 = computeAtrFromWindow(klines, i, 14);
                    BigDecimal atr50 = computeAtrFromWindow(klines, i, 50);
                    if (atr14 != null && atr50 != null && atr14.compareTo(BigDecimal.ZERO) > 0) {
                        // 当前短期 ATR 高于长期均值 → 市场更波动 → 缩小头寸
                        double coeff = atr50.doubleValue() / atr14.doubleValue();
                        coeff = Math.max(0.2, Math.min(1.0, coeff));
                        tradeAmount = baseTradeAmount.multiply(BigDecimal.valueOf(coeff))
                                .setScale(8, RoundingMode.DOWN);
                    }
                }
                BigDecimal fee = tradeAmount.multiply(config.getFeeRate());
                BigDecimal netAmount = tradeAmount.subtract(fee);
                position  = netAmount.divide(currentPrice, 8, RoundingMode.DOWN);
                capital   = capital.subtract(tradeAmount);
                entryPrice = currentPrice;
                entryTime  = currentKline.getOpenTime();
                inPosition = true;

                // ── 计算止损止盈触发价（与实盘 TradeExecutionService 逻辑一致）──────────
                // 优先级：ATR > 固定% > 不设置
                stopLossPrice   = null;
                takeProfitPrice = null;
                boolean hasAtrStop = strategy.getAtrStopMultiplier() != null
                        && strategy.getAtrStopMultiplier().compareTo(BigDecimal.ZERO) > 0;
                boolean hasPctStop = strategy.getStopLossPct() != null;
                boolean hasAtrTp   = strategy.getAtrTakeProfitMultiplier() != null
                        && strategy.getAtrTakeProfitMultiplier().compareTo(BigDecimal.ZERO) > 0;
                boolean hasPctTp   = strategy.getTakeProfitPct() != null;

                if (hasAtrStop || hasAtrTp) {
                    BigDecimal atr = computeAtrFromWindow(klines, i, 14);
                    if (atr != null && atr.compareTo(BigDecimal.ZERO) > 0) {
                        if (hasAtrStop) {
                            stopLossPrice = entryPrice
                                    .subtract(atr.multiply(strategy.getAtrStopMultiplier()))
                                    .setScale(8, RoundingMode.HALF_UP);
                        }
                        if (hasAtrTp) {
                            takeProfitPrice = entryPrice
                                    .add(atr.multiply(strategy.getAtrTakeProfitMultiplier()))
                                    .setScale(8, RoundingMode.HALF_UP);
                        }
                    }
                }
                if (stopLossPrice == null && hasPctStop) {
                    BigDecimal pct = strategy.getStopLossPct()
                            .divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP);
                    stopLossPrice = entryPrice.multiply(BigDecimal.ONE.subtract(pct))
                            .setScale(8, RoundingMode.HALF_UP);
                }
                if (takeProfitPrice == null && hasPctTp) {
                    BigDecimal pct = strategy.getTakeProfitPct()
                            .divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP);
                    takeProfitPrice = entryPrice.multiply(BigDecimal.ONE.add(pct))
                            .setScale(8, RoundingMode.HALF_UP);
                }

            } else if (inPosition && signal.getSignalType() == SignalType.SELL) {
                // 信号平仓
                BigDecimal exitValue = position.multiply(currentPrice);
                BigDecimal fee = exitValue.multiply(config.getFeeRate());
                BigDecimal netValue = exitValue.subtract(fee);

                BigDecimal cost = position.multiply(entryPrice);
                BigDecimal profit = netValue.subtract(cost);
                BigDecimal profitPercent = cost.compareTo(BigDecimal.ZERO) > 0
                        ? profit.divide(cost, 6, RoundingMode.HALF_UP).multiply(new BigDecimal("100"))
                        : BigDecimal.ZERO;

                trades.add(TradeRecord.builder()
                        .entryTime(entryTime)
                        .exitTime(currentKline.getOpenTime())
                        .type("LONG")
                        .entryPrice(entryPrice.setScale(8, RoundingMode.HALF_UP))
                        .exitPrice(currentPrice.setScale(8, RoundingMode.HALF_UP))
                        .quantity(position.setScale(8, RoundingMode.HALF_UP))
                        .profit(profit.setScale(2, RoundingMode.HALF_UP))
                        .profitPercent(profitPercent.setScale(2, RoundingMode.HALF_UP))
                        .exitReason("SIGNAL")
                        .build());

                capital         = capital.add(netValue);
                position        = BigDecimal.ZERO;
                inPosition      = false;
                stopLossPrice   = null;
                takeProfitPrice = null;
            }
        }

        // 如果回测结束时仍有持仓，按最后价格平仓
        if (inPosition && !klines.isEmpty()) {
            BigDecimal lastPrice = klines.get(klines.size() - 1).getClose();
            BigDecimal exitValue = position.multiply(lastPrice);
            BigDecimal fee = exitValue.multiply(config.getFeeRate());
            capital = capital.add(exitValue.subtract(fee));

            BigDecimal cost = position.multiply(entryPrice);
            BigDecimal profit = exitValue.subtract(fee).subtract(cost);
            BigDecimal profitPercent = cost.compareTo(BigDecimal.ZERO) > 0
                    ? profit.divide(cost, 6, RoundingMode.HALF_UP).multiply(new BigDecimal("100"))
                    : BigDecimal.ZERO;

            trades.add(TradeRecord.builder()
                    .entryTime(entryTime)
                    .exitTime(klines.get(klines.size() - 1).getOpenTime())
                    .type("LONG")
                    .entryPrice(entryPrice.setScale(8, RoundingMode.HALF_UP))
                    .exitPrice(lastPrice.setScale(8, RoundingMode.HALF_UP))
                    .quantity(position.setScale(8, RoundingMode.HALF_UP))
                    .profit(profit.setScale(2, RoundingMode.HALF_UP))
                    .profitPercent(profitPercent.setScale(2, RoundingMode.HALF_UP))
                    .exitReason("END_OF_BACKTEST")
                    .build());

            position = BigDecimal.ZERO;
        }

        // 添加最后一个资金曲线点
        equityCurve.add(EquityPoint.builder()
                .time(config.getEndTime())
                .equity(capital.setScale(2, RoundingMode.HALF_UP))
                .build());

        return calculateMetrics(strategy, config, capital, trades, equityCurve, maxDrawdown, maxDrawdownDuration);
    }

    private BacktestResultVO calculateMetrics(
            Strategy strategy,
            BacktestConfigDTO config,
            BigDecimal finalCapital,
            List<TradeRecord> trades,
            List<EquityPoint> equityCurve,
            BigDecimal maxDrawdown,
            int maxDrawdownDuration) {

        BigDecimal totalProfit = finalCapital.subtract(config.getInitialCapital());
        BigDecimal returnRate = config.getInitialCapital().compareTo(BigDecimal.ZERO) > 0
                ? totalProfit.divide(config.getInitialCapital(), 6, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"))
                : BigDecimal.ZERO;

        int totalTrades = trades.size();
        int winningTrades = (int) trades.stream()
                .filter(t -> t.getProfit().compareTo(BigDecimal.ZERO) > 0)
                .count();
        int losingTrades = (int) trades.stream()
                .filter(t -> t.getProfit().compareTo(BigDecimal.ZERO) < 0)
                .count();

        BigDecimal winRate = totalTrades > 0
                ? new BigDecimal(winningTrades).divide(new BigDecimal(totalTrades), 4, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"))
                : BigDecimal.ZERO;

        // 盈亏比 = 平均盈利 / 平均亏损
        BigDecimal avgWin = trades.stream()
                .filter(t -> t.getProfit().compareTo(BigDecimal.ZERO) > 0)
                .map(TradeRecord::getProfit)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal avgLoss = trades.stream()
                .filter(t -> t.getProfit().compareTo(BigDecimal.ZERO) < 0)
                .map(t -> t.getProfit().abs())
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal profitLossRatio;
        if (winningTrades > 0 && losingTrades > 0) {
            BigDecimal avgWinVal = avgWin.divide(new BigDecimal(winningTrades), 6, RoundingMode.HALF_UP);
            BigDecimal avgLossVal = avgLoss.divide(new BigDecimal(losingTrades), 6, RoundingMode.HALF_UP);
            profitLossRatio = avgLossVal.compareTo(BigDecimal.ZERO) > 0
                    ? avgWinVal.divide(avgLossVal, 2, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;
        } else {
            profitLossRatio = BigDecimal.ZERO;
        }

        // 简化夏普比率计算（假设无风险利率为0）
        BigDecimal sharpeRatio = BigDecimal.ZERO;
        if (trades.size() >= 2) {
            List<Double> returns = trades.stream()
                    .map(t -> t.getProfitPercent().doubleValue())
                    .toList();
            double mean = returns.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            double variance = returns.stream()
                    .mapToDouble(r -> (r - mean) * (r - mean))
                    .average()
                    .orElse(0);
            double stdDev = Math.sqrt(variance);
            if (stdDev > 0) {
                sharpeRatio = BigDecimal.valueOf(mean / stdDev).setScale(2, RoundingMode.HALF_UP);
            }
        }

        return BacktestResultVO.builder()
                .strategyId(strategy.getId())
                .strategyName(strategy.getName())
                .startTime(config.getStartTime())
                .endTime(config.getEndTime())
                .initialCapital(config.getInitialCapital().setScale(2, RoundingMode.HALF_UP))
                .finalCapital(finalCapital.setScale(2, RoundingMode.HALF_UP))
                .totalProfit(totalProfit.setScale(2, RoundingMode.HALF_UP))
                .returnRate(returnRate.setScale(2, RoundingMode.HALF_UP))
                .totalTrades(totalTrades)
                .winningTrades(winningTrades)
                .losingTrades(losingTrades)
                .winRate(winRate.setScale(2, RoundingMode.HALF_UP))
                .profitLossRatio(profitLossRatio)
                .maxDrawdown(maxDrawdown.setScale(2, RoundingMode.HALF_UP))
                .maxDrawdownDuration(maxDrawdownDuration)
                .sharpeRatio(sharpeRatio)
                .trades(trades)
                .equityCurve(equityCurve)
                .build();
    }

    /**
     * 从主周期K线窗口内联计算 ATR（Wilder 平滑，period=14）。
     * <p>
     * 复用 TradeExecutionService 中相同的算法，避免跨服务依赖。
     * 取 barIdx 前 period+1 根（含当前 bar）计算 True Range，再做 Wilder 平滑。
     *
     * @param klines  全量主周期K线（升序）
     * @param barIdx  当前 bar 在 klines 中的索引
     * @param period  ATR 周期（通常为 14）
     * @return ATR 值；若数据不足则返回 null
     */
    private BigDecimal computeAtrFromWindow(List<KLine> klines, int barIdx, int period) {
        // 需要 period+1 根才能算出 period 个 TR
        int from = Math.max(0, barIdx - period);
        List<KLine> window = klines.subList(from, barIdx + 1);
        if (window.size() < 2) return null;

        double[] trueRanges = new double[window.size() - 1];
        for (int j = 1; j < window.size(); j++) {
            KLine cur  = window.get(j);
            KLine prev = window.get(j - 1);
            double highLow   = cur.getHigh().doubleValue() - cur.getLow().doubleValue();
            double highClose = Math.abs(cur.getHigh().doubleValue() - prev.getClose().doubleValue());
            double lowClose  = Math.abs(cur.getLow().doubleValue()  - prev.getClose().doubleValue());
            trueRanges[j - 1] = Math.max(highLow, Math.max(highClose, lowClose));
        }

        int calcPeriod = Math.min(period, trueRanges.length);
        double atr = 0;
        for (int j = 0; j < calcPeriod; j++) atr += trueRanges[j];
        atr /= calcPeriod;
        // Wilder 平滑
        for (int j = calcPeriod; j < trueRanges.length; j++) {
            atr = (atr * (period - 1) + trueRanges[j]) / period;
        }
        return BigDecimal.valueOf(atr);
    }
}

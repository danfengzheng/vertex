package com.vertex.service.strategy.backtest;

import com.alibaba.fastjson2.JSON;
import com.vertex.api.usersetting.IUserSettingService;
import com.vertex.common.core.GlobalError;
import com.vertex.common.core.exception.BizException;
import com.vertex.model.vo.ai.AiBacktestAnalysisProgress;
import com.vertex.service.strategy.ai.AiAnalysisService;
import com.vertex.service.strategy.ai.AiAnalysisStore;
import com.vertex.service.strategy.ai.BacktestCacheKey;
import com.vertex.model.dto.strategy.BacktestConfigDTO;
import com.vertex.model.dto.strategy.StrategyIndicatorConfig;
import com.vertex.model.entity.quote.KLine;
import com.vertex.model.entity.strategy.IndicatorType;
import com.vertex.model.entity.strategy.Signal;
import com.vertex.model.entity.strategy.SignalType;
import com.vertex.model.entity.strategy.Strategy;
import com.vertex.model.vo.strategy.BacktestResultVO;
import com.vertex.model.vo.strategy.BacktestResultVO.EquityPoint;
import com.vertex.model.vo.strategy.BacktestResultVO.TradeRecord;
import com.vertex.service.quote.store.KLineStore;
import com.vertex.service.strategy.engine.IndicatorCalculationEngine;
import com.vertex.service.strategy.engine.SignalGenerator;
import com.vertex.service.strategy.indicator.IndicatorResult;
import com.vertex.service.strategy.mapper.StrategyMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import com.vertex.model.entity.quote.KLineInterval;
import com.vertex.model.entity.trading.PositionSide;
import com.vertex.model.entity.trading.StopLossStage;
import com.vertex.service.strategy.config.StrategyProperties;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

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
    private final IndicatorCalculationEngine indicatorEngine;
    private final SignalGenerator signalGenerator;
    private final StrategyProperties properties;

    /**
     * 用户个人设置（最大使用资金截断），可选注入。
     * user-service 未启动或在测试环境中未注册时降级为不限制。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private IUserSettingService userSettingService;

    /**
     * AI 分析存储（必装，用于回测结果缓存与 AI 分析持久化）。
     * 不依赖 GeminiClient——即使 AI 未启用，缓存能力仍然工作。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private AiAnalysisStore aiAnalysisStore;

    /**
     * AI 分析服务（vertex.ai.gemini.enabled=true 时才注册）。
     * null 时不影响缓存命中，只是不会触发批量 AI 分析。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private AiAnalysisService aiAnalysisService;

    /**
     * 执行策略回测。
     * <p>
     * <b>缓存语义</b>：以「策略白名单字段 + 回测配置」的内容哈希作为 cacheKey。
     * 同样配置 → 同样 cacheKey → 命中 RocksDB 中已有结果时直接返回（毫秒级），
     * 除非 {@code config.forceRefresh=true}。详见 {@link BacktestCacheKey}。
     * </p>
     */
    public BacktestResultVO runBacktest(BacktestConfigDTO config) {
        // 1. 加载策略
        Strategy strategy = strategyMapper.selectById(config.getStrategyId());
        if (strategy == null) {
            throw new BizException(GlobalError.STRATEGY_NOT_FOUND);
        }

        // ── 缓存命中检查 ────────────────────────────────────────────
        // 只要 aiAnalysisStore 注册了就启用缓存（不依赖 AI 服务）。
        String cacheKey = null;
        boolean forceRefresh = Boolean.TRUE.equals(config.getForceRefresh());
        if (aiAnalysisStore != null) {
            cacheKey = BacktestCacheKey.compute(strategy, config);
            if (!forceRefresh) {
                BacktestResultVO cached = aiAnalysisStore.getBacktestResult(cacheKey);
                if (cached != null) {
                    cached.setCached(Boolean.TRUE);
                    cached.setCacheKey(cacheKey);
                    if (cached.getCachedAt() == null) cached.setCachedAt(System.currentTimeMillis());
                    // 附带当前 AI 分析进度（如有）
                    AiBacktestAnalysisProgress prog = null;
                    if (aiAnalysisService != null) {
                        prog = aiAnalysisService.getProgress(cacheKey);
                        if (prog != null && prog.getStatus() != null) {
                            cached.setAiAnalysisStatus(prog.getStatus().name());
                        }
                    }

                    // 用户勾了 enableAiAnalysis，但旧缓存还没分析过（或之前失败）→
                    // 在「不重跑回测」的前提下，自动启动 AI 批量分析。
                    // 已 RUNNING/PENDING 时不重复触发（避免重复占线程池）。
                    boolean wantAi = Boolean.TRUE.equals(config.getEnableAiAnalysis())
                            && aiAnalysisService != null && aiAnalysisService.isEnabled();
                    boolean alreadyAnalyzing = prog != null && (
                            prog.getStatus() == AiBacktestAnalysisProgress.Status.RUNNING
                            || prog.getStatus() == AiBacktestAnalysisProgress.Status.PENDING);
                    if (wantAi && !alreadyAnalyzing
                            && cached.getTrades() != null && !cached.getTrades().isEmpty()) {
                        log.info("[Backtest cache HIT + AI trigger] cacheKey={} starting AI batch", cacheKey);
                        // 初始化进度为 PENDING，前端立刻能看到状态
                        AiBacktestAnalysisProgress initial = AiBacktestAnalysisProgress.builder()
                                .cacheKey(cacheKey)
                                .strategyId(strategy.getId())
                                .strategyName(strategy.getName())
                                .total(cached.getTrades().size())
                                .completed(0)
                                .failed(0)
                                .status(AiBacktestAnalysisProgress.Status.PENDING)
                                .startedAt(System.currentTimeMillis())
                                .updatedAt(System.currentTimeMillis())
                                .build();
                        aiAnalysisStore.saveProgress(initial);
                        cached.setAiAnalysisStatus(AiBacktestAnalysisProgress.Status.PENDING.name());
                        try {
                            aiAnalysisService.analyzeBacktestBatchAsync(cacheKey, strategy, cached);
                        } catch (Exception e) {
                            log.warn("[AI] submit batch on cache-hit failed: cacheKey={}, err={}",
                                    cacheKey, e.getMessage());
                        }
                    }

                    log.info("[Backtest cache HIT] strategy={} cacheKey={} trades={}",
                            strategy.getName(), cacheKey, cached.getTrades() == null ? 0 : cached.getTrades().size());
                    return cached;
                }
            } else {
                log.info("[Backtest cache] forceRefresh=true, recomputing strategy={} cacheKey={}",
                        strategy.getName(), cacheKey);
            }
        }

        List<StrategyIndicatorConfig> indicatorConfigs = JSON.parseArray(
                strategy.getIndicatorConfigs(), StrategyIndicatorConfig.class);
        if (indicatorConfigs == null || indicatorConfigs.isEmpty()) {
            throw new BizException(GlobalError.STRATEGY_CONFIG_ERROR);
        }

        // 解析出场指标配置（可为空，为空时跳过指标出场评估）
        List<StrategyIndicatorConfig> exitConfigs = parseExitConfigs(strategy);

        // 2. 按周期计算所需数据量，加载多周期K线（与 StrategyEngineService 一致）
        Map<KLineInterval, Integer> requiredByInterval =
                indicatorEngine.computeMaxRequired(strategy, indicatorConfigs);
        // 合并出场配置的周期需求，确保 K 线全部预加载
        if (!exitConfigs.isEmpty()) {
            Map<KLineInterval, Integer> exitRequired = indicatorEngine.computeMaxRequired(strategy, exitConfigs);
            exitRequired.forEach((iv, req) ->
                    requiredByInterval.merge(iv, req, Math::max));
        }
        Set<KLineInterval> allIntervals = new HashSet<>(requiredByInterval.keySet());
        allIntervals.add(strategy.getInterval());

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

        // 若策略配置了独立的 ATR 止损周期，且该周期尚未被指标加载，则额外补充
        KLineInterval atrIv = strategy.getAtrInterval();
        if (atrIv != null && !allKlinesByInterval.containsKey(atrIv)) {
            int atrReq = 50; // ATR14 × 3 + 缓冲
            long ivMillis = atrIv.getMillis();
            long prefetchStart = config.getStartTime() - ivMillis * (atrReq * mainWarmup + 10);
            List<KLine> ivKlines = klineStore.query(
                    strategy.getExchange(), strategy.getSymbol(), atrIv,
                    prefetchStart, config.getEndTime(), Integer.MAX_VALUE, true);
            allKlinesByInterval.put(atrIv, ivKlines);
            requiredByInterval.put(atrIv, atrReq);
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
        BacktestResultVO result = simulateTrades(strategy, indicatorConfigs, exitConfigs,
                mainKlines, allKlinesByInterval, config, mainRequired);

        // ── 缓存写入 + 异步 AI 批量分析 ───────────────────────────────
        if (aiAnalysisStore != null && cacheKey != null) {
            result.setCacheKey(cacheKey);
            result.setCached(Boolean.FALSE);
            result.setCachedAt(System.currentTimeMillis());

            // 异步：勾选了 enableAiAnalysis 且 AI 服务可用时，提交批量任务
            boolean wantAi = Boolean.TRUE.equals(config.getEnableAiAnalysis())
                    && aiAnalysisService != null && aiAnalysisService.isEnabled();
            if (wantAi) {
                // 标记初始进度为 PENDING，方便前端立即看到「分析中」状态
                AiBacktestAnalysisProgress initial = AiBacktestAnalysisProgress.builder()
                        .cacheKey(cacheKey)
                        .strategyId(strategy.getId())
                        .strategyName(strategy.getName())
                        .total(result.getTrades() == null ? 0 : result.getTrades().size())
                        .completed(0)
                        .failed(0)
                        .status(AiBacktestAnalysisProgress.Status.PENDING)
                        .startedAt(System.currentTimeMillis())
                        .updatedAt(System.currentTimeMillis())
                        .build();
                aiAnalysisStore.saveProgress(initial);
                result.setAiAnalysisStatus(AiBacktestAnalysisProgress.Status.PENDING.name());

                try {
                    aiAnalysisService.analyzeBacktestBatchAsync(cacheKey, strategy, result);
                } catch (Exception e) {
                    log.warn("[AI] submit backtest batch failed: cacheKey={}, err={}",
                            cacheKey, e.getMessage());
                }
            }

            // 写入缓存：结果体本身不含 trades 顺序之外的随机性，可放心缓存
            aiAnalysisStore.saveBacktestResult(cacheKey, result);
            log.info("[Backtest cache MISS] strategy={} cacheKey={} trades={} stored",
                    strategy.getName(), cacheKey,
                    result.getTrades() == null ? 0 : result.getTrades().size());
        }
        return result;
    }

    /**
     * 从 BacktestBarProvider 获取 ATR 计算所需的 K 线窗口。
     * 优先使用 atrInterval，若该周期无数据则回退到主周期。
     */
    private List<KLine> getAtrBars(BacktestBarProvider barProvider,
                                   KLineInterval atrInterval,
                                   KLineInterval primaryInterval,
                                   int maxKlineHistory) {
        List<KLine> bars = barProvider.getBars(atrInterval, Math.min(50 * 3 + 10, maxKlineHistory));
        if (bars.isEmpty() && !atrInterval.equals(primaryInterval)) {
            bars = barProvider.getBars(primaryInterval, Math.min(50 * 3 + 10, maxKlineHistory));
        }
        return bars;
    }

    private BacktestResultVO simulateTrades(
            Strategy strategy,
            List<StrategyIndicatorConfig> configs,
            List<StrategyIndicatorConfig> exitConfigs,
            List<KLine> klines,
            Map<KLineInterval, List<KLine>> allKlinesByInterval,
            BacktestConfigDTO config,
            int mainRequiredDataPoints) {

        int maxKlineHistory = properties.getEngine().getMaxKlineHistory();
        int warmup = properties.getEngine().getWarmupMultiplier();

        // ATR 止损专用周期（与实盘 TradeExecutionService 保持一致）
        KLineInterval effectiveAtrInterval = strategy.getAtrInterval() != null
                ? strategy.getAtrInterval() : strategy.getInterval();

        BigDecimal capital = config.getInitialCapital();
        BigDecimal position = BigDecimal.ZERO;
        BigDecimal entryPrice = BigDecimal.ZERO;
        Long entryTime = null;
        boolean inPosition = false;
        // 止损止盈触发价（开仓时按策略配置计算，null 表示未配置）
        BigDecimal stopLossPrice = null;
        BigDecimal takeProfitPrice = null;
        // 移动ATR止损状态（null = 未启用）
        StopLossStage stopLossStage = null;
        BigDecimal highestPrice    = null; // LONG 极值追踪
        // 出场条件：开仓后经历的K线计数（时间止损用）
        int openBarCount = 0;
        // 合约杠杆（默认1=现货，合约策略使用实际杠杆）
        int leverage = (strategy.getLeverage() != null && strategy.getLeverage() > 1) ? strategy.getLeverage() : 1;
        // 合约模式（leverage > 1 时启用：支持多空双向、SELL 信号开空）
        boolean isFutures = leverage > 1;
        // 当前持仓方向（null=无仓位，LONG=多，SHORT=空）
        PositionSide positionSide = null;
        // 开仓时实际占用的保证金（合约平仓时返还保证金+盈亏，而非返还名义价值）
        BigDecimal marginPaid = BigDecimal.ZERO;

        List<TradeRecord> trades = new ArrayList<>();
        List<EquityPoint> equityCurve = new ArrayList<>();

        // 峰值回撤止损：追踪持仓期间最优价格（LONG=最高价，SHORT=最低价），null 表示未开仓
        BigDecimal peakPrice = null;
        boolean hasTrailingDrop = strategy.getTrailingDropPct() != null
                && strategy.getTrailingDropPct().compareTo(BigDecimal.ZERO) > 0;

        // SuperTrend 动态止损（superTrendSlOffsetPct > 0 时启用）
        boolean hasSuperTrendSl = strategy.getSuperTrendSlOffsetPct() != null
                && strategy.getSuperTrendSlOffsetPct().compareTo(BigDecimal.ZERO) > 0;
        BigDecimal superTrendStopLossPrice = null; // 当前 SuperTrend 止损价，null=未初始化/已平仓

        // 分阶段止盈状态（与实盘保持一致；启用条件：size1 > 0 且 pct1 > 0）
        boolean hasStagedTp = strategy.getTakeProfitSize1() != null
                && strategy.getTakeProfitSize1().compareTo(BigDecimal.ZERO) > 0
                && strategy.getTakeProfitPct1() != null
                && strategy.getTakeProfitPct1().compareTo(BigDecimal.ZERO) > 0;
        java.util.List<BigDecimal> stagedPcts  = new java.util.ArrayList<>(3);
        java.util.List<BigDecimal> stagedSizes = new java.util.ArrayList<>(3);
        if (hasStagedTp) {
            stagedPcts.add(strategy.getTakeProfitPct1());
            stagedSizes.add(strategy.getTakeProfitSize1());
            if (strategy.getTakeProfitSize2() != null
                    && strategy.getTakeProfitSize2().compareTo(BigDecimal.ZERO) > 0
                    && strategy.getTakeProfitPct2() != null
                    && strategy.getTakeProfitPct2().compareTo(BigDecimal.ZERO) > 0) {
                stagedPcts.add(strategy.getTakeProfitPct2());
                stagedSizes.add(strategy.getTakeProfitSize2());
                if (strategy.getTakeProfitSize3() != null
                        && strategy.getTakeProfitSize3().compareTo(BigDecimal.ZERO) > 0
                        && strategy.getTakeProfitPct3() != null
                        && strategy.getTakeProfitPct3().compareTo(BigDecimal.ZERO) > 0) {
                    stagedPcts.add(strategy.getTakeProfitPct3());
                    stagedSizes.add(strategy.getTakeProfitSize3());
                }
            }
        }
        int takeProfitStage = 0;             // 已触发的档数（0/1/2/3）
        BigDecimal initialPosition = BigDecimal.ZERO; // 持仓建立时的基线数量
        BigDecimal initialMargin   = BigDecimal.ZERO; // 持仓建立时的保证金，用于按比例摊销 marginPaid
        Integer breakevenAfterStage = strategy.getMoveStopToBreakevenAfterStage();

        // ── 个人设置：单笔开仓最大使用资金（U/USDT）截断 ──────────────────────────────
        // 与实盘 TradeExecutionService.applyUserMaxTradeCapital 行为一致：
        //   按策略创建者读取一次（回测期间该值不变，循环外缓存避免 N 次 DB 查询）。
        // null / <= 0 / 服务未注入 → maxTradeCapital 为 null，不截断。
        BigDecimal maxTradeCapital = null;
        if (userSettingService != null && strategy.getCreateBy() != null) {
            try {
                maxTradeCapital = userSettingService.getMaxTradeCapital(strategy.getCreateBy());
            } catch (Exception e) {
                log.warn("[Backtest] Failed to read user maxTradeCapital for userId={}: {}",
                        strategy.getCreateBy(), e.getMessage());
            }
        }
        if (maxTradeCapital != null) {
            log.info("[Backtest] User maxTradeCapital cap active: userId={} max={}",
                    strategy.getCreateBy(), maxTradeCapital);
        }

        // ── 止损熔断 ─────────────────────────────────────────────────────────────────
        // 开关启用时，止损（STOP_LOSS / TRAILING_DROP）触发且实际亏损即暂停开仓 24 小时。
        // 信号出场、止盈、时间止损、指标出场不触发，移动止损在盈利区间触发也不触发。
        boolean hasPauseOnStopLoss = strategy.getPauseOnStopLoss() != null
                && strategy.getPauseOnStopLoss() == 1;
        long tradingPausedUntilMs = -1;                // 熔断结束时间戳（-1=未暂停）

        BigDecimal peakEquity = capital;
        BigDecimal maxDrawdown = BigDecimal.ZERO;
        int maxDrawdownDuration = 0;
        int currentDrawdownDuration = 0;

        // 创建 BacktestBarProvider（预加载所有周期历史数据，单调指针 O(1) getBars）
        BacktestBarProvider barProvider = new BacktestBarProvider(
                allKlinesByInterval, strategy.getExchange(), strategy.getSymbol());

        // 滑动窗口模拟（以策略主周期为时间轴）
        // 起始索引取 warmupSize-1，确保首次评估时就能取到完整的预热窗口
        int mainWarmupSize = Math.min(mainRequiredDataPoints * warmup + 10, maxKlineHistory);
        int startIdx = Math.max(mainRequiredDataPoints, mainWarmupSize - 1);

        for (int i = startIdx; i < klines.size(); i++) {
            KLine currentKline = klines.get(i);

            // 推进各周期指针到当前触发时间（单调递增，总复杂度 O(n+m)）
            barProvider.advance(currentKline.getOpenTime(), strategy.getInterval());

            // 仅处理回测时间范围内的K线（指针推进不受此限制）
            if (currentKline.getOpenTime() < config.getStartTime()) {
                continue;
            }


            // 调用指标计算引擎（每周期取一次 bar，追加 PartialBar，计算所有指标）
            List<IndicatorResult> results = indicatorEngine.computeAll(strategy, configs, barProvider);
            if (!IndicatorCalculationEngine.hasEnoughData(results)) {
                continue;
            }

            // 生成信号（三阶段：前置过滤 → 投票加权 → 后置方向校验）
            Signal signal = signalGenerator.evaluate(strategy, configs, results, List.of(currentKline));

            // 当前 bar 收盘价（用于权益计算与信号平仓）
            BigDecimal currentPrice = currentKline.getClose();

            // ── 峰值更新：每根 K 线先更新最优价格，再检查回撤止损 ────────────────────────
            if (inPosition && hasTrailingDrop) {
                boolean isShortPeak = positionSide == PositionSide.SHORT;
                if (!isShortPeak) {
                    // LONG：用当根 K 线最高价更新峰值
                    BigDecimal high = currentKline.getHigh() != null ? currentKline.getHigh() : currentPrice;
                    if (peakPrice == null || high.compareTo(peakPrice) > 0) peakPrice = high;
                } else {
                    // SHORT：用当根 K 线最低价更新峰值（最低价越低越优）
                    BigDecimal low = currentKline.getLow() != null ? currentKline.getLow() : currentPrice;
                    if (peakPrice == null || low.compareTo(peakPrice) < 0) peakPrice = low;
                }
            }

            // ── 止损止盈检查（优先于信号，使用 K 线 High/Low 判断触发）───────────────────
            // 同一根 K 线同时触及止损和止盈时，止损优先（保守原则）
            boolean closedByStopOrTp = false;
            if (inPosition) {
                boolean isShortPos = positionSide == PositionSide.SHORT;
                // SHORT：止损在上方（high >= SL），止盈在下方（low <= TP）
                // LONG ：止损在下方（low  <= SL），止盈在上方（high >= TP）
                boolean stopHit = stopLossPrice != null && (isShortPos
                        ? currentKline.getHigh() != null && currentKline.getHigh().compareTo(stopLossPrice) >= 0
                        : currentKline.getLow()  != null && currentKline.getLow().compareTo(stopLossPrice)  <= 0);
                boolean tpHit = !stopHit && takeProfitPrice != null && (isShortPos
                        ? currentKline.getLow()  != null && currentKline.getLow().compareTo(takeProfitPrice)  <= 0
                        : currentKline.getHigh() != null && currentKline.getHigh().compareTo(takeProfitPrice) >= 0);

                if (stopHit || tpHit) {
                    // ── 分阶段止盈：TP 命中且启用分阶段，可能是部分平仓 ─────
                    boolean isStagedPartial = tpHit && hasStagedTp
                            && takeProfitStage < stagedPcts.size();
                    BigDecimal exitPrice = stopHit ? stopLossPrice : takeProfitPrice;
                    String exitReason   = stopHit ? "STOP_LOSS" : "TAKE_PROFIT";

                    if (isStagedPartial) { // 分阶段 TP 部分平仓 / 末档扫尾
                        int nextStage = takeProfitStage + 1;
                        boolean isLastConfigured = (nextStage == stagedPcts.size());
                        BigDecimal sumSize = BigDecimal.ZERO;
                        for (BigDecimal sz : stagedSizes) sumSize = sumSize.add(sz);
                        boolean sumIsFull = sumSize.compareTo(BigDecimal.valueOf(100)) >= 0;
                        boolean sweepAll = isLastConfigured && sumIsFull;

                        // 本档减仓量：末档扫尾 → 剩余全部；否则按 initialPosition × size_i / 100
                        BigDecimal closeQty;
                        if (sweepAll) {
                            closeQty = position;
                        } else {
                            BigDecimal sizePct = stagedSizes.get(takeProfitStage)
                                    .divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP);
                            closeQty = initialPosition.multiply(sizePct)
                                    .setScale(8, RoundingMode.DOWN);
                            // 防御：partial >= 剩余 → 退化为扫尾
                            if (closeQty.compareTo(position) >= 0) {
                                closeQty = position;
                                sweepAll = true;
                            }
                        }
                        BigDecimal partialExitValue = closeQty.multiply(exitPrice);
                        BigDecimal partialFee = partialExitValue.multiply(config.getFeeRate());
                        BigDecimal partialPnl = isShortPos
                                ? closeQty.multiply(entryPrice.subtract(exitPrice))
                                : closeQty.multiply(exitPrice.subtract(entryPrice));
                        BigDecimal partialProfit = partialPnl.subtract(partialFee);
                        // 按比例返还保证金：分摊 initialMargin × (closeQty / initialPosition)
                        BigDecimal returnedMargin = initialMargin.compareTo(BigDecimal.ZERO) > 0
                                ? initialMargin.multiply(closeQty)
                                        .divide(initialPosition, 8, RoundingMode.HALF_UP)
                                : BigDecimal.ZERO;
                        BigDecimal partialProfitPct = returnedMargin.compareTo(BigDecimal.ZERO) > 0
                                ? partialProfit.divide(returnedMargin, 6, RoundingMode.HALF_UP)
                                        .multiply(new BigDecimal("100"))
                                : BigDecimal.ZERO;

                        trades.add(TradeRecord.builder()
                                .entryTime(entryTime)
                                .exitTime(currentKline.getOpenTime())
                                .type(isShortPos ? "SHORT" : "LONG")
                                .entryPrice(entryPrice.setScale(8, RoundingMode.HALF_UP))
                                .exitPrice(exitPrice.setScale(8, RoundingMode.HALF_UP))
                                .quantity(closeQty.setScale(8, RoundingMode.HALF_UP))
                                .profit(partialProfit.setScale(2, RoundingMode.HALF_UP))
                                .profitPercent(partialProfitPct.setScale(2, RoundingMode.HALF_UP))
                                .exitReason("TAKE_PROFIT_" + nextStage)
                                .build());

                        capital  = capital.add(returnedMargin).add(partialProfit);
                        position = position.subtract(closeQty);
                        marginPaid = marginPaid.subtract(returnedMargin)
                                .max(BigDecimal.ZERO);
                        takeProfitStage = nextStage;
                        currentPrice = exitPrice;

                        // 保本钩子：触发指定档后将止损上移到入场价（与实盘一致）
                        if (breakevenAfterStage != null && breakevenAfterStage == nextStage) {
                            stopLossPrice = entryPrice.setScale(8, RoundingMode.HALF_UP);
                            stopLossStage = StopLossStage.BREAKEVEN;
                        }

                        if (sweepAll || position.compareTo(BigDecimal.ZERO) <= 0) {
                            // 已扫尾或剩余为零：完全平仓
                            inPosition     = false;
                            positionSide   = null;
                            stopLossPrice  = null;
                            takeProfitPrice = null;
                            stopLossStage  = null;
                            highestPrice   = null;
                            peakPrice      = null;
                            openBarCount   = 0;
                            superTrendStopLossPrice = null;
                            marginPaid     = BigDecimal.ZERO;
                            initialPosition = BigDecimal.ZERO;
                            initialMargin   = BigDecimal.ZERO;
                            takeProfitStage = 0;
                            closedByStopOrTp = true;
                        } else if (nextStage < stagedPcts.size()) {
                            // 仍有后续档：更新 takeProfitPrice 为下一档
                            BigDecimal nextPct = stagedPcts.get(nextStage)
                                    .divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP);
                            BigDecimal dirSign = isShortPos ? new BigDecimal("-1") : BigDecimal.ONE;
                            takeProfitPrice = entryPrice.multiply(BigDecimal.ONE.add(dirSign.multiply(nextPct)))
                                    .setScale(8, RoundingMode.HALF_UP);
                            // 持仓继续，不重置 inPosition
                        } else {
                            // 已经是最后已配置档但 Σ<100% → runner 模式：清掉 takeProfitPrice，
                            // 持仓继续由止损族（固定 SL / SuperTrend / 峰值回撤 / 反向信号）接管。
                            takeProfitPrice = null;
                        }
                    } else { // 非分阶段路径 → 全平
                    BigDecimal exitValue = position.multiply(exitPrice);
                    BigDecimal fee       = exitValue.multiply(config.getFeeRate());
                    // SHORT：价格下跌盈利；LONG：价格上涨盈利
                    BigDecimal pnl = isShortPos
                            ? position.multiply(entryPrice.subtract(exitPrice))
                            : position.multiply(exitPrice.subtract(entryPrice));
                    BigDecimal profit    = pnl.subtract(fee);
                    BigDecimal profitPct = marginPaid.compareTo(BigDecimal.ZERO) > 0
                            ? profit.divide(marginPaid, 6, RoundingMode.HALF_UP).multiply(new BigDecimal("100"))
                            : BigDecimal.ZERO;

                    trades.add(TradeRecord.builder()
                            .entryTime(entryTime)
                            .exitTime(currentKline.getOpenTime())
                            .type(isShortPos ? "SHORT" : "LONG")
                            .entryPrice(entryPrice.setScale(8, RoundingMode.HALF_UP))
                            .exitPrice(exitPrice.setScale(8, RoundingMode.HALF_UP))
                            .quantity(position.setScale(8, RoundingMode.HALF_UP))
                            .profit(profit.setScale(2, RoundingMode.HALF_UP))
                            .profitPercent(profitPct.setScale(2, RoundingMode.HALF_UP))
                            .exitReason(exitReason)
                            .build());

                    capital        = capital.add(marginPaid).add(profit);
                    marginPaid     = BigDecimal.ZERO;
                    position       = BigDecimal.ZERO;
                    inPosition     = false;
                    positionSide   = null;
                    stopLossPrice  = null;
                    takeProfitPrice = null;
                    stopLossStage  = null;
                    highestPrice   = null;
                    peakPrice      = null;
                    openBarCount   = 0;
                    superTrendStopLossPrice = null;
                    initialPosition = BigDecimal.ZERO;
                    initialMargin   = BigDecimal.ZERO;
                    takeProfitStage = 0;
                    closedByStopOrTp = true;
                    // 止损/止盈当根 K 线权益以出场价结算
                    currentPrice = exitPrice;

                    // 止损熔断：止损触发且实际亏损时，暂停开仓 24 小时
                    if (hasPauseOnStopLoss && stopHit && profit.compareTo(BigDecimal.ZERO) < 0) {
                        boolean notPaused = tradingPausedUntilMs <= 0
                                || currentKline.getOpenTime() >= tradingPausedUntilMs;
                        if (notPaused) {
                            tradingPausedUntilMs = currentKline.getOpenTime() + 24 * 3_600_000L;
                            log.info("[StopLossPause] Backtest 熔断(SL): strategy={} 止损亏损={}, 暂停至 {}",
                                    strategy.getName(),
                                    profit.setScale(2, RoundingMode.HALF_UP),
                                    tradingPausedUntilMs);
                        }
                    }
                    } // end else (非分阶段路径)
                }

                // ── SuperTrend 动态止损检查（SL/TP 未触发时，优先于峰值回撤）────────────────
                // 与实盘 StopLossTakeProfitTask 优先级一致：固定SL > SuperTrend > 峰值回撤
                if (!closedByStopOrTp && hasSuperTrendSl && superTrendStopLossPrice != null
                        && superTrendStopLossPrice.compareTo(BigDecimal.ZERO) > 0) {
                    boolean stHit = isShortPos
                            ? currentKline.getHigh() != null && currentKline.getHigh().compareTo(superTrendStopLossPrice) >= 0
                            : currentKline.getLow()  != null && currentKline.getLow().compareTo(superTrendStopLossPrice)  <= 0;
                    if (stHit) {
                        BigDecimal exitPrice = superTrendStopLossPrice;
                        BigDecimal exitVal   = position.multiply(exitPrice);
                        BigDecimal fee       = exitVal.multiply(config.getFeeRate());
                        BigDecimal pnl       = isShortPos
                                ? position.multiply(entryPrice.subtract(exitPrice))
                                : position.multiply(exitPrice.subtract(entryPrice));
                        BigDecimal profit    = pnl.subtract(fee);
                        BigDecimal profitPct = marginPaid.compareTo(BigDecimal.ZERO) > 0
                                ? profit.divide(marginPaid, 6, RoundingMode.HALF_UP).multiply(new BigDecimal("100"))
                                : BigDecimal.ZERO;
                        trades.add(TradeRecord.builder()
                                .entryTime(entryTime).exitTime(currentKline.getOpenTime())
                                .type(isShortPos ? "SHORT" : "LONG")
                                .entryPrice(entryPrice.setScale(8, RoundingMode.HALF_UP))
                                .exitPrice(exitPrice.setScale(8, RoundingMode.HALF_UP))
                                .quantity(position.setScale(8, RoundingMode.HALF_UP))
                                .profit(profit.setScale(2, RoundingMode.HALF_UP))
                                .profitPercent(profitPct.setScale(2, RoundingMode.HALF_UP))
                                .exitReason("SUPERTREND_STOP").build());
                        capital      = capital.add(marginPaid).add(profit);
                        marginPaid   = BigDecimal.ZERO; position = BigDecimal.ZERO;
                        inPosition   = false; positionSide = null;
                        stopLossPrice = null; takeProfitPrice = null;
                        stopLossStage = null; highestPrice = null;
                        peakPrice     = null; openBarCount  = 0;
                        superTrendStopLossPrice = null;
                        initialPosition = BigDecimal.ZERO; initialMargin = BigDecimal.ZERO; takeProfitStage = 0;
                        closedByStopOrTp = true;
                        currentPrice = exitPrice;
                        // 止损熔断：SuperTrend 止损触发且实际亏损时，暂停开仓 24 小时
                        if (hasPauseOnStopLoss && profit.compareTo(BigDecimal.ZERO) < 0) {
                            boolean notPaused = tradingPausedUntilMs <= 0
                                    || currentKline.getOpenTime() >= tradingPausedUntilMs;
                            if (notPaused) {
                                tradingPausedUntilMs = currentKline.getOpenTime() + 24 * 3_600_000L;
                                log.info("[StopLossPause] Backtest 熔断(SuperTrendSL): strategy={} 止损亏损={}, 暂停至 {}",
                                        strategy.getName(),
                                        profit.setScale(2, RoundingMode.HALF_UP),
                                        tradingPausedUntilMs);
                            }
                        }
                    }
                }

                // ── 峰值回撤止损检查（SL/TP 未触发时才执行）────────────────────
                if (!closedByStopOrTp && hasTrailingDrop && peakPrice != null) {
                    BigDecimal dropPct = strategy.getTrailingDropPct()
                            .divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP);
                    boolean dropTriggered;
                    BigDecimal dropExitPrice;
                    if (isShortPos) {
                        // SHORT：从最低价反弹 dropPct% 触发
                        dropExitPrice = peakPrice.multiply(BigDecimal.ONE.add(dropPct))
                                .setScale(8, RoundingMode.HALF_UP);
                        dropTriggered = currentKline.getHigh() != null
                                && currentKline.getHigh().compareTo(dropExitPrice) >= 0;
                    } else {
                        // LONG：从最高价下跌 dropPct% 触发
                        dropExitPrice = peakPrice.multiply(BigDecimal.ONE.subtract(dropPct))
                                .setScale(8, RoundingMode.HALF_UP);
                        dropTriggered = currentKline.getLow() != null
                                && currentKline.getLow().compareTo(dropExitPrice) <= 0;
                    }
                    if (dropTriggered) {
                        BigDecimal exitVal = position.multiply(dropExitPrice);
                        BigDecimal fee     = exitVal.multiply(config.getFeeRate());
                        BigDecimal pnl     = isShortPos
                                ? position.multiply(entryPrice.subtract(dropExitPrice))
                                : position.multiply(dropExitPrice.subtract(entryPrice));
                        BigDecimal profit  = pnl.subtract(fee);
                        BigDecimal profitPct = marginPaid.compareTo(BigDecimal.ZERO) > 0
                                ? profit.divide(marginPaid, 6, RoundingMode.HALF_UP).multiply(new BigDecimal("100"))
                                : BigDecimal.ZERO;
                        trades.add(TradeRecord.builder()
                                .entryTime(entryTime).exitTime(currentKline.getOpenTime())
                                .type(isShortPos ? "SHORT" : "LONG")
                                .entryPrice(entryPrice.setScale(8, RoundingMode.HALF_UP))
                                .exitPrice(dropExitPrice)
                                .quantity(position.setScale(8, RoundingMode.HALF_UP))
                                .profit(profit.setScale(2, RoundingMode.HALF_UP))
                                .profitPercent(profitPct.setScale(2, RoundingMode.HALF_UP))
                                .exitReason("TRAILING_DROP").build());
                        capital      = capital.add(marginPaid).add(profit);
                        marginPaid   = BigDecimal.ZERO;
                        position     = BigDecimal.ZERO;
                        inPosition   = false;
                        positionSide = null;
                        stopLossPrice = null; takeProfitPrice = null;
                        stopLossStage = null; highestPrice = null;
                        peakPrice    = null;
                        openBarCount = 0;
                        superTrendStopLossPrice = null;
                        initialPosition = BigDecimal.ZERO; initialMargin = BigDecimal.ZERO; takeProfitStage = 0;
                        closedByStopOrTp = true;
                        currentPrice = dropExitPrice;

                        // 止损熔断：峰值回撤止损触发且实际亏损时，暂停开仓 24 小时
                        if (hasPauseOnStopLoss && profit.compareTo(BigDecimal.ZERO) < 0) {
                            boolean notPaused = tradingPausedUntilMs <= 0
                                    || currentKline.getOpenTime() >= tradingPausedUntilMs;
                            if (notPaused) {
                                tradingPausedUntilMs = currentKline.getOpenTime() + 24 * 3_600_000L;
                                log.info("[StopLossPause] Backtest 熔断(TrailingDrop): strategy={} 止损亏损={}, 暂停至 {}",
                                        strategy.getName(),
                                        profit.setScale(2, RoundingMode.HALF_UP),
                                        tradingPausedUntilMs);
                            }
                        }
                    }
                }
            }

            // ── 出场条件检查（时间止损 + 指标出场，SL/TP 触发后跳过） ─────────────────
            // 入场/信号平仓门槛：与实盘 StrategyEngineService.meetsStrengthThreshold() 默认 60 保持一致
            int minStrength = (strategy.getMinSignalStrength() != null && strategy.getMinSignalStrength() > 0)
                    ? strategy.getMinSignalStrength() : 60;
            // 独立出场指标门槛：与实盘 TradeExecutionService.processExitConditions() 完全一致，默认 0
            int exitIndicatorMinStrength = (strategy.getMinSignalStrength() != null && strategy.getMinSignalStrength() > 0)
                    ? strategy.getMinSignalStrength() : 0;
            if (inPosition && !closedByStopOrTp) {
                openBarCount++;

                // 时间止损：超过最大持仓K线数强制平仓
                if (strategy.getMaxHoldingBars() != null && openBarCount >= strategy.getMaxHoldingBars()) {
                    boolean isShortExit = positionSide == PositionSide.SHORT;
                    BigDecimal exitValue = position.multiply(currentPrice);
                    BigDecimal fee = exitValue.multiply(config.getFeeRate());
                    BigDecimal pnl = isShortExit
                            ? position.multiply(entryPrice.subtract(currentPrice))
                            : position.multiply(currentPrice.subtract(entryPrice));
                    BigDecimal profit = pnl.subtract(fee);
                    BigDecimal profitPct = marginPaid.compareTo(BigDecimal.ZERO) > 0
                            ? profit.divide(marginPaid, 6, RoundingMode.HALF_UP).multiply(new BigDecimal("100"))
                            : BigDecimal.ZERO;

                    trades.add(TradeRecord.builder()
                            .entryTime(entryTime)
                            .exitTime(currentKline.getOpenTime())
                            .type(isShortExit ? "SHORT" : "LONG")
                            .entryPrice(entryPrice.setScale(8, RoundingMode.HALF_UP))
                            .exitPrice(currentPrice.setScale(8, RoundingMode.HALF_UP))
                            .quantity(position.setScale(8, RoundingMode.HALF_UP))
                            .profit(profit.setScale(2, RoundingMode.HALF_UP))
                            .profitPercent(profitPct.setScale(2, RoundingMode.HALF_UP))
                            .exitReason("MAX_BARS")
                            .build());

                    capital         = capital.add(marginPaid).add(profit);
                    marginPaid      = BigDecimal.ZERO;
                    position        = BigDecimal.ZERO;
                    inPosition      = false;
                    positionSide    = null;
                    stopLossPrice   = null;
                    takeProfitPrice = null;
                    stopLossStage   = null;
                    highestPrice    = null;
                    peakPrice       = null;
                    openBarCount    = 0;
                    superTrendStopLossPrice = null;
                    initialPosition = BigDecimal.ZERO;
                    initialMargin   = BigDecimal.ZERO;
                    takeProfitStage = 0;
                    closedByStopOrTp = true; // 当根K线已平仓，阻止信号重新开仓
                }

                // 指标出场（exitConfigs 不为空且当前仍持仓时）
                if (inPosition && !exitConfigs.isEmpty()) {
                    List<IndicatorResult> exitResults = indicatorEngine.computeAll(strategy, exitConfigs, barProvider);
                    if (IndicatorCalculationEngine.hasEnoughData(exitResults)) {
                        Signal exitSignal = signalGenerator.evaluate(strategy, exitConfigs, exitResults,
                                List.of(currentKline));
                        // 出场指标：多头在 SELL 信号时出场，空头在 BUY 信号时出场
                        boolean indExitTriggered = exitSignal.getSignalStrength() != null
                                && exitSignal.getSignalStrength() >= exitIndicatorMinStrength
                                && ((positionSide == PositionSide.LONG  && exitSignal.getSignalType() == SignalType.SELL)
                                 || (positionSide == PositionSide.SHORT && exitSignal.getSignalType() == SignalType.BUY));
                        if (indExitTriggered) {
                            boolean isShortExit2 = positionSide == PositionSide.SHORT;
                            BigDecimal exitValue = position.multiply(currentPrice);
                            BigDecimal fee = exitValue.multiply(config.getFeeRate());
                            BigDecimal pnl = isShortExit2
                                    ? position.multiply(entryPrice.subtract(currentPrice))
                                    : position.multiply(currentPrice.subtract(entryPrice));
                            BigDecimal profit = pnl.subtract(fee);
                            BigDecimal profitPct = marginPaid.compareTo(BigDecimal.ZERO) > 0
                                    ? profit.divide(marginPaid, 6, RoundingMode.HALF_UP).multiply(new BigDecimal("100"))
                                    : BigDecimal.ZERO;

                            trades.add(TradeRecord.builder()
                                    .entryTime(entryTime)
                                    .exitTime(currentKline.getOpenTime())
                                    .type(isShortExit2 ? "SHORT" : "LONG")
                                    .entryPrice(entryPrice.setScale(8, RoundingMode.HALF_UP))
                                    .exitPrice(currentPrice.setScale(8, RoundingMode.HALF_UP))
                                    .quantity(position.setScale(8, RoundingMode.HALF_UP))
                                    .profit(profit.setScale(2, RoundingMode.HALF_UP))
                                    .profitPercent(profitPct.setScale(2, RoundingMode.HALF_UP))
                                    .exitReason("INDICATOR_EXIT")
                                    .build());

                            capital         = capital.add(marginPaid).add(profit);
                            marginPaid      = BigDecimal.ZERO;
                            position        = BigDecimal.ZERO;
                            inPosition      = false;
                            positionSide    = null;
                            stopLossPrice   = null;
                            takeProfitPrice = null;
                            stopLossStage   = null;
                            highestPrice    = null;
                            peakPrice       = null;
                            openBarCount    = 0;
                            superTrendStopLossPrice = null;
                            initialPosition = BigDecimal.ZERO;
                            initialMargin   = BigDecimal.ZERO;
                            takeProfitStage = 0;
                            closedByStopOrTp = true; // 当根K线已平仓，阻止信号重新开仓
                        }
                    }
                }
            }

            // 当前权益（止损/止盈出场后 inPosition=false，capital 已更新）
            // 合约：权益 = 可用资金 + 保证金 + 未实现盈亏
            // SHORT 未实现盈亏 = position × (entryPrice - currentPrice)
            BigDecimal currentEquity;
            if (inPosition) {
                BigDecimal unrealizedPnl = positionSide == PositionSide.SHORT
                        ? position.multiply(entryPrice.subtract(currentPrice))
                        : position.multiply(currentPrice.subtract(entryPrice));
                currentEquity = capital.add(marginPaid).add(unrealizedPnl);
            } else {
                currentEquity = capital;
            }

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

            // ── 信号处理（现货仅做多；合约支持多空双向）─────────────────────────────────────
            // minStrength 已在出场条件块中声明，默认 60 与实盘保持一致
            boolean hasValidSignal = !closedByStopOrTp
                    && signal.getSignalStrength() != null && signal.getSignalStrength() >= minStrength;
            // toOpen：信号处理完成后需要新开仓的方向（null=不开）
            PositionSide toOpen = null;

            if (hasValidSignal) {
                SignalType sigType = signal.getSignalType();

                // ── BUY 信号：合约先平空 → 再标记开多 ─────────────────────────────
                if (sigType == SignalType.BUY) {
                    // 合约：空单反向平仓
                    if (isFutures && positionSide == PositionSide.SHORT) {
                        BigDecimal exitVal = position.multiply(currentPrice);
                        BigDecimal exitFee = exitVal.multiply(config.getFeeRate());
                        BigDecimal exitPnl = position.multiply(entryPrice.subtract(currentPrice));
                        BigDecimal exitProfit = exitPnl.subtract(exitFee);
                        BigDecimal exitPct = marginPaid.compareTo(BigDecimal.ZERO) > 0
                                ? exitProfit.divide(marginPaid, 6, RoundingMode.HALF_UP).multiply(new BigDecimal("100"))
                                : BigDecimal.ZERO;
                        trades.add(TradeRecord.builder()
                                .entryTime(entryTime).exitTime(currentKline.getOpenTime())
                                .type("SHORT")
                                .entryPrice(entryPrice.setScale(8, RoundingMode.HALF_UP))
                                .exitPrice(currentPrice.setScale(8, RoundingMode.HALF_UP))
                                .quantity(position.setScale(8, RoundingMode.HALF_UP))
                                .profit(exitProfit.setScale(2, RoundingMode.HALF_UP))
                                .profitPercent(exitPct.setScale(2, RoundingMode.HALF_UP))
                                .exitReason("SIGNAL_REVERSE").build());
                        capital = capital.add(marginPaid).add(exitProfit);
                        marginPaid = BigDecimal.ZERO; position = BigDecimal.ZERO;
                        inPosition = false; positionSide = null;
                        stopLossPrice = null; takeProfitPrice = null;
                        stopLossStage = null; highestPrice = null; peakPrice = null; openBarCount = 0;
                        superTrendStopLossPrice = null;
                        initialPosition = BigDecimal.ZERO; initialMargin = BigDecimal.ZERO; takeProfitStage = 0;
                    }
                    // 无仓位 → 开多
                    if (!inPosition) toOpen = PositionSide.LONG;
                }

                // ── SELL 信号：平多 → 合约再标记开空 ──────────────────────────────
                else if (sigType == SignalType.SELL) {
                    // 平多
                    if (positionSide == PositionSide.LONG) {
                        BigDecimal exitVal = position.multiply(currentPrice);
                        BigDecimal exitFee = exitVal.multiply(config.getFeeRate());
                        BigDecimal exitPnl = position.multiply(currentPrice.subtract(entryPrice));
                        BigDecimal exitProfit = exitPnl.subtract(exitFee);
                        BigDecimal exitPct = marginPaid.compareTo(BigDecimal.ZERO) > 0
                                ? exitProfit.divide(marginPaid, 6, RoundingMode.HALF_UP).multiply(new BigDecimal("100"))
                                : BigDecimal.ZERO;
                        trades.add(TradeRecord.builder()
                                .entryTime(entryTime).exitTime(currentKline.getOpenTime())
                                .type("LONG")
                                .entryPrice(entryPrice.setScale(8, RoundingMode.HALF_UP))
                                .exitPrice(currentPrice.setScale(8, RoundingMode.HALF_UP))
                                .quantity(position.setScale(8, RoundingMode.HALF_UP))
                                .profit(exitProfit.setScale(2, RoundingMode.HALF_UP))
                                .profitPercent(exitPct.setScale(2, RoundingMode.HALF_UP))
                                .exitReason("SIGNAL").build());
                        capital = capital.add(marginPaid).add(exitProfit);
                        marginPaid = BigDecimal.ZERO; position = BigDecimal.ZERO;
                        inPosition = false; positionSide = null;
                        stopLossPrice = null; takeProfitPrice = null;
                        stopLossStage = null; highestPrice = null; peakPrice = null; openBarCount = 0;
                        superTrendStopLossPrice = null;
                        initialPosition = BigDecimal.ZERO; initialMargin = BigDecimal.ZERO; takeProfitStage = 0;
                    }
                    // 合约：无仓位 → 开空
                    if (isFutures && !inPosition) toOpen = PositionSide.SHORT;
                }
            }

            // ── 止损熔断：若处于暂停期则拦截本根 K 线的开仓 ────────────────────────────
            // 熔断由各止损出场块（STOP_LOSS / TRAILING_DROP 且亏损）写入 tradingPausedUntilMs。
            // 信号出场、止盈、时间止损、指标出场不触发，移动止损在盈利区间触发亦不触发。
            if (hasPauseOnStopLoss && tradingPausedUntilMs > 0
                    && currentKline.getOpenTime() < tradingPausedUntilMs) {
                if (toOpen != null) {
                    log.debug("[DailyLoss] Backtest: 暂停期内跳过开仓 strategy={}", strategy.getName());
                }
                toOpen = null;
            }

            // ── 开仓执行（LONG 或 SHORT）──────────────────────────────────────────────
            // toOpen 由上方信号处理设置，null 表示本根 K 线不需要新开仓
            if (toOpen != null) {
                boolean openShort = toOpen == PositionSide.SHORT;
                // ATR 仓位控制系数（策略配置了 atrStopMultiplier 时生效）
                BigDecimal baseTradeAmount = capital.multiply(config.getPositionRatio());
                BigDecimal tradeAmount = baseTradeAmount;
                boolean hasAtrPositionSizing = strategy.getAtrStopMultiplier() != null
                        && strategy.getAtrStopMultiplier().compareTo(BigDecimal.ZERO) > 0;
                if (hasAtrPositionSizing) {
                    List<KLine> atrWindow0 = getAtrBars(barProvider, effectiveAtrInterval, strategy.getInterval(), maxKlineHistory);
                    BigDecimal atr14 = computeAtrFromList(atrWindow0, 14);
                    BigDecimal atr50 = computeAtrFromList(atrWindow0, 50);
                    if (atr14 != null && atr50 != null && atr14.compareTo(BigDecimal.ZERO) > 0) {
                        double coeff = atr50.doubleValue() / atr14.doubleValue();
                        coeff = Math.max(0.2, Math.min(1.0, coeff));
                        tradeAmount = baseTradeAmount.multiply(BigDecimal.valueOf(coeff))
                                .setScale(8, RoundingMode.DOWN);
                    }
                }
                // 个人设置：tradeAmount 超过用户配置的 maxTradeCapital 时截断
                // （与实盘 TradeExecutionService.applyUserMaxTradeCapital 行为一致；
                //  ATR 仓位系数应用之后再截断，确保上限是硬性约束）
                if (maxTradeCapital != null && tradeAmount.compareTo(maxTradeCapital) > 0) {
                    log.debug("[Backtest] tradeAmount {} exceeds maxTradeCapital, capped to {}",
                            tradeAmount, maxTradeCapital);
                    tradeAmount = maxTradeCapital;
                }
                // 手续费按名义价值计算（杠杆×保证金）
                BigDecimal openFee = tradeAmount.multiply(BigDecimal.valueOf(leverage)).multiply(config.getFeeRate());
                BigDecimal netMargin = tradeAmount.subtract(openFee);
                position   = netMargin.multiply(BigDecimal.valueOf(leverage)).divide(currentPrice, 8, RoundingMode.DOWN);
                capital    = capital.subtract(tradeAmount);
                marginPaid = tradeAmount;
                entryPrice = currentPrice;
                entryTime  = currentKline.getOpenTime();
                inPosition = true;
                positionSide = toOpen;
                openBarCount = 0;
                // 分阶段止盈基线：仅在启用时记录，否则保持零
                initialPosition = hasStagedTp ? position : BigDecimal.ZERO;
                initialMargin   = hasStagedTp ? marginPaid : BigDecimal.ZERO;
                takeProfitStage = 0;
                // 峰值回撤止损：开仓时初始化峰值为入场价，与实盘 StopLossTakeProfitTask 行为一致
                // 实盘 fallback：position.getHighestPrice() != null ? ... : position.getEntryPrice()
                peakPrice = hasTrailingDrop ? entryPrice : null;

                // ── 止损止盈计算（LONG：SL 下方 TP 上方；SHORT：SL 上方 TP 下方）──────
                // dir = 1（多）或 -1（空），统一公式：SL = entry - dir×mult×ATR，TP = entry + dir×mult×ATR
                BigDecimal dir = openShort ? new BigDecimal("-1") : BigDecimal.ONE;
                stopLossPrice   = null;
                takeProfitPrice = null;
                stopLossStage   = null;
                highestPrice    = null; // LONG=最高价追踪；SHORT=最低价追踪（语义相反，见移动止损块）
                boolean hasTrailingStop = strategy.getInitialStopMultiplier() != null
                        && strategy.getInitialStopMultiplier().compareTo(BigDecimal.ZERO) > 0;
                boolean hasAtrStop = strategy.getAtrStopMultiplier() != null
                        && strategy.getAtrStopMultiplier().compareTo(BigDecimal.ZERO) > 0;
                boolean hasPctStop = strategy.getStopLossPct() != null;
                // 启用分阶段止盈时与 ATR 倍数 / 固定百分比止盈互斥（与实盘 setStopLossTakeProfit 一致）
                boolean hasAtrTp   = !hasStagedTp
                        && strategy.getAtrTakeProfitMultiplier() != null
                        && strategy.getAtrTakeProfitMultiplier().compareTo(BigDecimal.ZERO) > 0;
                boolean hasPctTp   = !hasStagedTp && strategy.getTakeProfitPct() != null;

                List<KLine> atrWindow = getAtrBars(barProvider, effectiveAtrInterval, strategy.getInterval(), maxKlineHistory);
                if (hasTrailingStop) {
                    BigDecimal atr = computeAtrFromList(atrWindow, 14);
                    if (atr != null && atr.compareTo(BigDecimal.ZERO) > 0) {
                        // SL = entry - dir × initialMult × ATR
                        stopLossPrice = entryPrice
                                .subtract(dir.multiply(atr).multiply(strategy.getInitialStopMultiplier()))
                                .setScale(8, RoundingMode.HALF_UP);
                        stopLossStage = StopLossStage.INITIAL;
                        highestPrice  = entryPrice; // 追踪起点
                    }
                } else if (hasAtrStop || hasAtrTp) {
                    BigDecimal atr = computeAtrFromList(atrWindow, 14);
                    if (atr != null && atr.compareTo(BigDecimal.ZERO) > 0) {
                        if (hasAtrStop) {
                            stopLossPrice = entryPrice
                                    .subtract(dir.multiply(atr).multiply(strategy.getAtrStopMultiplier()))
                                    .setScale(8, RoundingMode.HALF_UP);
                        }
                        if (hasAtrTp) {
                            takeProfitPrice = entryPrice
                                    .add(dir.multiply(atr).multiply(strategy.getAtrTakeProfitMultiplier()))
                                    .setScale(8, RoundingMode.HALF_UP);
                        }
                    }
                }
                if (!hasTrailingStop && !hasAtrStop && hasPctStop) {
                    BigDecimal pct = strategy.getStopLossPct()
                            .divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP);
                    // LONG: entry × (1 - pct); SHORT: entry × (1 + pct)
                    stopLossPrice = entryPrice.multiply(BigDecimal.ONE.subtract(dir.multiply(pct)))
                            .setScale(8, RoundingMode.HALF_UP);
                }
                if (hasStagedTp) {
                    // 分阶段启用：忽略 atrTakeProfitMultiplier 与 takeProfitPct，
                    // 首档 takeProfitPrice 由 pct1 计算；后续档由 partial 命中后切换。
                    BigDecimal pct1 = strategy.getTakeProfitPct1()
                            .divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP);
                    takeProfitPrice = entryPrice.multiply(BigDecimal.ONE.add(dir.multiply(pct1)))
                            .setScale(8, RoundingMode.HALF_UP);
                } else if (!hasAtrTp && hasPctTp) {
                    BigDecimal pct = strategy.getTakeProfitPct()
                            .divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP);
                    // LONG: entry × (1 + pct); SHORT: entry × (1 - pct)
                    takeProfitPrice = entryPrice.multiply(BigDecimal.ONE.add(dir.multiply(pct)))
                            .setScale(8, RoundingMode.HALF_UP);
                }
            }


            // ── 移动ATR止损状态机：每根K线收盘后推进（与实盘 TradeExecutionService 一致）────
            // 在信号处理之后执行，新止损价在下一根K线的止损检查中生效
            // SHORT：阈值和极值方向全部取反（dir=-1），highestPrice 在空单时实为"最低价"
            if (inPosition && stopLossStage != null) {
                boolean trailShort = positionSide == PositionSide.SHORT;
                List<KLine> atrWin = getAtrBars(barProvider, effectiveAtrInterval, strategy.getInterval(), maxKlineHistory);
                BigDecimal atr = computeAtrFromList(atrWin, 14);
                if (atr != null && atr.compareTo(BigDecimal.ZERO) > 0) {
                    BigDecimal closePrice = currentKline.getClose();
                    switch (stopLossStage) {
                        case INITIAL -> {
                            // LONG：close >= entry + activation × ATR
                            // SHORT：close <= entry - activation × ATR
                            if (strategy.getBreakevenActivationMultiplier() != null) {
                                BigDecimal threshold = entryPrice
                                        .add(atr.multiply(strategy.getBreakevenActivationMultiplier())
                                                .multiply(trailShort ? new BigDecimal("-1") : BigDecimal.ONE));
                                boolean activated = trailShort
                                        ? closePrice.compareTo(threshold) <= 0
                                        : closePrice.compareTo(threshold) >= 0;
                                if (activated) {
                                    stopLossPrice = entryPrice.setScale(8, RoundingMode.HALF_UP);
                                    stopLossStage = StopLossStage.BREAKEVEN;
                                }
                            }
                        }
                        case BREAKEVEN -> {
                            if (strategy.getTrailingActivationMultiplier() != null) {
                                BigDecimal threshold = entryPrice
                                        .add(atr.multiply(strategy.getTrailingActivationMultiplier())
                                                .multiply(trailShort ? new BigDecimal("-1") : BigDecimal.ONE));
                                boolean activated = trailShort
                                        ? closePrice.compareTo(threshold) <= 0
                                        : closePrice.compareTo(threshold) >= 0;
                                if (activated) {
                                    highestPrice  = closePrice; // SHORT 时实为最低价
                                    stopLossStage = StopLossStage.TRAILING;
                                    if (strategy.getTrailingDistanceMultiplier() != null) {
                                        // LONG：highest - dist×ATR；SHORT：lowest + dist×ATR
                                        BigDecimal newStop = trailShort
                                                ? highestPrice.add(atr.multiply(strategy.getTrailingDistanceMultiplier()))
                                                        .setScale(8, RoundingMode.HALF_UP)
                                                : highestPrice.subtract(atr.multiply(strategy.getTrailingDistanceMultiplier()))
                                                        .setScale(8, RoundingMode.HALF_UP);
                                        boolean improved = trailShort
                                                ? (stopLossPrice == null || newStop.compareTo(stopLossPrice) < 0)
                                                : (stopLossPrice == null || newStop.compareTo(stopLossPrice) > 0);
                                        if (improved) stopLossPrice = newStop;
                                    }
                                }
                            }
                        }
                        case TRAILING -> {
                            // LONG：极值只升；SHORT：极值只降
                            boolean newExtreme = trailShort
                                    ? closePrice.compareTo(highestPrice) < 0
                                    : closePrice.compareTo(highestPrice) > 0;
                            if (newExtreme) highestPrice = closePrice;
                            if (strategy.getTrailingDistanceMultiplier() != null) {
                                BigDecimal newStop = trailShort
                                        ? highestPrice.add(atr.multiply(strategy.getTrailingDistanceMultiplier()))
                                                .setScale(8, RoundingMode.HALF_UP)
                                        : highestPrice.subtract(atr.multiply(strategy.getTrailingDistanceMultiplier()))
                                                .setScale(8, RoundingMode.HALF_UP);
                                // LONG：止损只朝上移；SHORT：止损只朝下移
                                boolean improved = trailShort
                                        ? (stopLossPrice == null || newStop.compareTo(stopLossPrice) < 0)
                                        : (stopLossPrice == null || newStop.compareTo(stopLossPrice) > 0);
                                if (improved) stopLossPrice = newStop;
                            }
                        }
                    }
                }
            }

            // ── SuperTrend 动态止损价更新（每根K线收盘后，下一根K线止损检查时生效）─────────
            // 时序与实盘一致：K线N收盘 → 更新止损价 → K线N+1的High/Low检查触发
            if (inPosition && hasSuperTrendSl) {
                IndicatorResult stResult = results.stream()
                        .filter(r -> r.getType() == IndicatorType.SUPERTREND && r.getValues() != null)
                        .findFirst().orElse(null);
                if (stResult != null) {
                    Double superTrendVal = stResult.getValues().get("superTrend");
                    Double trendVal      = stResult.getValues().get("trend");
                    // superTrendVal > 0 防御：指标输出 0（数据不足/异常）时不更新
                    if (superTrendVal != null && superTrendVal > 0 && trendVal != null) {
                        boolean trendUp  = trendVal > 0;
                        boolean isShortPos = positionSide == PositionSide.SHORT;
                        // 趋势反转时冻结，不更新（与实盘 TradeExecutionService 一致）
                        boolean trendMatchesPosition = (!isShortPos && trendUp) || (isShortPos && !trendUp);
                        if (trendMatchesPosition) {
                            BigDecimal pct = strategy.getSuperTrendSlOffsetPct()
                                    .divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP);
                            BigDecimal newStop = isShortPos
                                    ? BigDecimal.valueOf(superTrendVal).multiply(BigDecimal.ONE.add(pct))
                                            .setScale(8, RoundingMode.HALF_UP)
                                    : BigDecimal.valueOf(superTrendVal).multiply(BigDecimal.ONE.subtract(pct))
                                            .setScale(8, RoundingMode.HALF_UP);
                            // 止损价只向有利方向移动（与实盘 TradeExecutionService 一致）
                            boolean favorable = superTrendStopLossPrice == null
                                    || (isShortPos  && newStop.compareTo(superTrendStopLossPrice) < 0)
                                    || (!isShortPos && newStop.compareTo(superTrendStopLossPrice) > 0);
                            if (favorable) superTrendStopLossPrice = newStop;
                        }
                    }
                }
            }
        }

        // 如果回测结束时仍有持仓，按最后价格平仓
        if (inPosition && !klines.isEmpty()) {
            BigDecimal lastPrice = klines.get(klines.size() - 1).getClose();
            boolean isShortEnd = positionSide == PositionSide.SHORT;
            BigDecimal exitValue = position.multiply(lastPrice);
            BigDecimal fee = exitValue.multiply(config.getFeeRate());
            BigDecimal pnl = isShortEnd
                    ? position.multiply(entryPrice.subtract(lastPrice))
                    : position.multiply(lastPrice.subtract(entryPrice));
            BigDecimal profit = pnl.subtract(fee);
            BigDecimal profitPercent = marginPaid.compareTo(BigDecimal.ZERO) > 0
                    ? profit.divide(marginPaid, 6, RoundingMode.HALF_UP).multiply(new BigDecimal("100"))
                    : BigDecimal.ZERO;

            capital = capital.add(marginPaid).add(profit);

            trades.add(TradeRecord.builder()
                    .entryTime(entryTime)
                    .exitTime(klines.get(klines.size() - 1).getOpenTime())
                    .type(isShortEnd ? "SHORT" : "LONG")
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
     * 从任意周期K线列表末尾计算 ATR（Wilder 平滑）。
     * 用于支持 atrInterval 时从非主周期K线窗口中取数。
     *
     * @param klines 已按 openTime 升序排列的K线列表（来自 BacktestBarProvider.getBars()）
     * @param period ATR 周期（通常为 14）
     * @return ATR 值；若数据不足则返回 null
     */
    private BigDecimal computeAtrFromList(List<KLine> klines, int period) {
        if (klines == null || klines.size() < 2) return null;
        // 取末尾 period×3+1 根以保证 Wilder 平滑预热充分
        int from = Math.max(0, klines.size() - (period * 3 + 1));
        List<KLine> window = klines.subList(from, klines.size());
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
        for (int j = calcPeriod; j < trueRanges.length; j++) {
            atr = (atr * (period - 1) + trueRanges[j]) / period;
        }
        return BigDecimal.valueOf(atr);
    }

    /** 解析出场指标配置 JSON；未配置时返回空列表 */
    private List<StrategyIndicatorConfig> parseExitConfigs(Strategy strategy) {
        if (strategy.getExitIndicatorConfigs() == null || strategy.getExitIndicatorConfigs().isBlank()) {
            return Collections.emptyList();
        }
        List<StrategyIndicatorConfig> configs = JSON.parseArray(
                strategy.getExitIndicatorConfigs(), StrategyIndicatorConfig.class);
        return configs != null ? configs : Collections.emptyList();
    }
}

package com.vertex.service.strategy.engine;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.vertex.common.core.exception.BizException;
import com.vertex.common.core.GlobalError;
import com.vertex.model.dto.strategy.StrategyIndicatorConfig;
import com.vertex.model.entity.quote.KLine;
import com.vertex.model.entity.quote.KLineInterval;
import com.vertex.model.entity.strategy.Signal;
import com.vertex.model.entity.strategy.SignalType;
import com.vertex.model.entity.strategy.Strategy;
import com.vertex.service.quote.store.KLineStore;
import com.vertex.service.strategy.config.StrategyProperties;
import com.vertex.model.entity.strategy.IndicatorType;
import com.vertex.service.strategy.indicator.IndicatorResult;
import com.vertex.service.strategy.mapper.SignalMapper;
import com.vertex.service.strategy.mapper.StrategyMapper;
import com.vertex.service.strategy.store.SignalStore;
import com.vertex.api.trading.ITradeExecutionListener;
import com.vertex.service.strategy.ai.AiAnalysisService;
import com.vertex.service.strategy.notify.CompositeSignalNotifier;
import com.vertex.service.strategy.websocket.SignalPushService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 策略引擎服务（编排器）
 * <p>
 * 负责：加载策略 → 创建 BarProvider → 调用指标计算引擎 → 生成信号 → 双写存储 → WebSocket推送
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StrategyEngineService {

    private final StrategyMapper strategyMapper;
    private final SignalMapper signalMapper;
    private final SignalStore signalStore;
    private final KLineStore klineStore;
    private final SignalGenerator signalGenerator;
    private final IndicatorCalculationEngine indicatorEngine;
    private final StrategyProperties properties;

    /** 信号推送服务（可选依赖，WebSocket 未配置时为 null） */
    @Autowired(required = false)
    private SignalPushService signalPushService;

    /** 交易执行监听器（可选依赖，trading 未启用时为 null） */
    @Autowired(required = false)
    private ITradeExecutionListener tradeExecutionListener;

    /** 信号通知器聚合（自动注入所有 SignalNotifier 实现，无实现时为空列表） */
    private final CompositeSignalNotifier compositeSignalNotifier;

    /** AI 分析服务（软依赖，vertex.ai.gemini.enabled=false 时降级 no-op） */
    @Autowired(required = false)
    private AiAnalysisService aiAnalysisService;

    /** 节流：记录每个策略的上次执行时间戳（毫秒） */
    private final ConcurrentHashMap<Long, Long> lastEvalTimeMap = new ConcurrentHashMap<>();

    /**
     * 同一策略的 runStrategy 必须串行执行，防止以下并发场景：
     * <ul>
     *   <li>WebSocket 断线重连时同一 K 线 close 事件被触发两次（@Async 两个线程并发）</li>
     *   <li>同策略配置了多周期指标，不同周期 close 事件同时触发</li>
     * </ul>
     * 两个线程若并发通过信号幂等 SELECT-COUNT 检查，会产生重复信号并触发双重交易。
     * 采用 tryLock（非阻塞）：若另一线程正在执行，当前线程直接跳过本次评估。
     */
    private final ConcurrentHashMap<Long, ReentrantLock> strategyRunLocks = new ConcurrentHashMap<>();

    /**
     * 处理K线更新事件
     * <p>
     * 查找所有匹配 exchange + symbol 的已启用策略，
     * 然后检查该策略是否有任何一个指标使用了更新的 K线周期。
     */
    public void processKLineUpdate(String exchange, String symbol, KLineInterval interval, List<KLine> klines) {
        // 如果配置了仅处理已收盘K线，则过滤
        if (properties.getEngine().isOnlyClosedKlines()) {
            klines = klines.stream().filter(k -> Boolean.TRUE.equals(k.getClosed())).toList();
            if (klines.isEmpty()) {
                return;
            }
        }

        // 提取触发本次评估的最新 K线 openTime，作为查询窗口的上界。
        // 由于 StrategyEventListener 使用 @Async，事件可能在积压后延迟处理，
        // 此时 RocksDB 中已写入多根更新的已收盘K线。若不锁定上界，
        // 策略引擎会取"当前最新"K线而非"触发事件时的最新"K线，
        // 导致窗口向后漂移（例如 01:00 触发的事件在 01:45 才处理，窗口错位到 01:45）。
        final long triggeringKlineTime = klines.stream()
                .mapToLong(KLine::getOpenTime)
                .max()
                .orElse(0L);

        // 查找所有匹配 exchange + symbol 的已启用策略（不限 interval）
        LambdaQueryWrapper<Strategy> wrapper = new LambdaQueryWrapper<Strategy>()
                .eq(Strategy::getExchange, exchange)
                .eq(Strategy::getSymbol, symbol)
                .eq(Strategy::getEnabled, 1)
                .eq(Strategy::getInterval,interval)
                .eq(Strategy::getDeleted, 0);

        List<Strategy> strategies = strategyMapper.selectList(wrapper);
        if (strategies.isEmpty()) {
            return;
        }

        for (Strategy strategy : strategies) {
            try {
                List<StrategyIndicatorConfig> configs = parseConfigs(strategy);
                Set<KLineInterval> usedIntervals = collectAllIntervals(strategy, configs);
                // 仅当更新的 interval 是该策略用到的周期之一时才执行
                if (usedIntervals.contains(interval)) {
                    // 节流：非收盘K线模式下，限制评估频率
                    if (!properties.getEngine().isOnlyClosedKlines() && isThrottled(strategy.getId())) {
                        log.debug("Strategy [{}] throttled, skipping evaluation", strategy.getName());
                        continue;
                    }
                    runStrategy(strategy, triggeringKlineTime);
                }
            } catch (Exception e) {
                log.error("Strategy [{}] execution failed: {}", strategy.getName(), e.getMessage(), e);
            }
        }
    }

    /**
     * 手动触发策略执行（使用当前最新K线，不限窗口上界）
     */
    public void runStrategyNow(Long strategyId) {
        Strategy strategy = strategyMapper.selectById(strategyId);
        if (strategy == null) {
            throw new BizException(GlobalError.STRATEGY_NOT_FOUND);
        }
        runStrategy(strategy, null);
    }

    /**
     * 执行单个策略（支持多周期K线）
     *
     * @param triggeringKlineTime 触发本次评估的K线 openTime（毫秒）；
     *                            null 表示手动触发，使用当前最新K线。
     *                            设置此值可防止 @Async 延迟导致的窗口漂移。
     */
    private void runStrategy(Strategy strategy, Long triggeringKlineTime) {
        // 应用层 per-strategy 锁：tryLock 方式，防止同一策略被并发评估产生重复信号与双重交易。
        // 自动触发时（triggeringKlineTime != null）直接跳过；手动触发（null）也适用。
        ReentrantLock runLock = strategyRunLocks.computeIfAbsent(strategy.getId(), id -> new ReentrantLock(true));
        if (!runLock.tryLock()) {
            log.debug("Strategy [{}] is already being evaluated by another thread, skipping concurrent trigger",
                    strategy.getName());
            return;
        }
        try {
            runStrategyInternal(strategy, triggeringKlineTime);
        } finally {
            runLock.unlock();
            // 手动触发结束后清理锁，防止长期积累；自动触发频繁执行的策略保留锁实例（复用成本低）
            if (triggeringKlineTime == null) {
                strategyRunLocks.remove(strategy.getId(), runLock);
            }
        }
    }

    private void runStrategyInternal(Strategy strategy, Long triggeringKlineTime) {
        List<StrategyIndicatorConfig> configs = parseConfigs(strategy);
        if (configs == null || configs.isEmpty()) {
            log.warn("Strategy [{}] has no indicator configs", strategy.getName());
            return;
        }

        // 创建 LiveBarProvider（封装 K 线查询细节：onlyClosedKlines 过滤、翻转升序、触发时间锁定）
        LiveBarProvider barProvider = new LiveBarProvider(
                klineStore, properties,
                strategy.getExchange(), strategy.getSymbol(),
                triggeringKlineTime);

        // 调用指标计算引擎：
        //   1. 按周期统计最大 requiredDataPoints
        //   2. 每个周期通过 barProvider 取一次 bar（warmupMultiplier 预热）
        //   3. 为大周期追加 PartialBar（消除 Lookahead Bias）
        //   4. 调用各指标 calculate()，汇总结果
        List<IndicatorResult> results = indicatorEngine.computeAll(strategy, configs, barProvider);
        if (!IndicatorCalculationEngine.hasEnoughData(results)) {
            log.debug("Strategy [{}] skipped: no interval has sufficient K-line data", strategy.getName());
            return;
        }

        // 获取主周期最近 bar（用于信号的 price / signalTime；barProvider 保持触发时间锁定）
        List<KLine> primaryKlines = barProvider.getBars(strategy.getInterval(), 1);

        // 生成信号（三阶段：前置硬性过滤 → 投票加权 → 后置方向校验）
        Signal signal = signalGenerator.evaluate(strategy, configs, results, primaryKlines);

        // 移动ATR止损：每根K线收盘后触发（与信号无关，始终执行）
        if (tradeExecutionListener != null && strategy.getInitialStopMultiplier() != null
                && strategy.getInitialStopMultiplier().compareTo(BigDecimal.ZERO) > 0) {
            try {
                // 确定 ATR 使用的 K 线周期：优先 atrInterval，回退到主周期
                KLineInterval effectiveAtrInterval = strategy.getAtrInterval() != null
                        ? strategy.getAtrInterval() : strategy.getInterval();

                // barProvider 可按需获取任意周期的 bar（升序、已收盘）
                List<KLine> atrKlines = barProvider.getBars(effectiveAtrInterval,
                        Math.min(50, properties.getEngine().getMaxKlineHistory()));

                if (atrKlines != null && !atrKlines.isEmpty()) {
                    BigDecimal atrValue = computeAtrFromKlines(atrKlines, 14);
                    if (atrValue != null) {
                        tradeExecutionListener.onKLineClose(strategy, atrValue, atrKlines);
                    }
                } else {
                    log.warn("[Trailing SL] No K-lines for atrInterval={} in strategy '{}'",
                            effectiveAtrInterval, strategy.getName());
                }
            } catch (Exception e) {
                log.error("Trailing stop update failed for strategy [{}]: {}", strategy.getName(), e.getMessage(), e);
            }
        }

        // ── SuperTrend 动态止损更新（每根K线收盘后触发，与信号方向无关）──────────
        // 仅当策略配置了 superTrendSlOffsetPct 且指标结果中包含 SUPERTREND 数据时执行。
        // 趋势与持仓方向一致时更新 position.superTrendStopLoss；反转时冻结，不更新。
        if (tradeExecutionListener != null
                && strategy.getSuperTrendSlOffsetPct() != null
                && strategy.getSuperTrendSlOffsetPct().compareTo(BigDecimal.ZERO) > 0) {
            try {
                IndicatorResult superTrendResult = results.stream()
                        .filter(r -> r.getType() == IndicatorType.SUPERTREND && r.getValues() != null)
                        .findFirst()
                        .orElse(null);
                if (superTrendResult != null) {
                    Double superTrendVal = superTrendResult.getValues().get("superTrend");
                    Double trendVal      = superTrendResult.getValues().get("trend");
                    // superTrendVal > 0 防御：若指标输出 0（数据不足/异常），不触发止损写入
                    if (superTrendVal != null && superTrendVal > 0 && trendVal != null) {
                        BigDecimal superTrendValue = BigDecimal.valueOf(superTrendVal);
                        boolean trendUp = trendVal > 0;
                        tradeExecutionListener.onSuperTrendStopUpdate(strategy, superTrendValue, trendUp);
                    }
                }
            } catch (Exception e) {
                log.error("[SuperTrend SL] Update failed for strategy [{}]: {}", strategy.getName(), e.getMessage(), e);
            }
        }

        // ── 出场条件评估（每根K线收盘后检查，与入场信号无关） ─────────────────
        // autoTrade=1 时才有实仓需要管理；此块在 NEUTRAL 返回之前执行，确保 barCount 每根K线都更新
        if (tradeExecutionListener != null && strategy.getAutoTrade() != null && strategy.getAutoTrade() == 1) {
            List<StrategyIndicatorConfig> exitConfigs = parseExitConfigs(strategy);
            SignalType exitSignalType = SignalType.NEUTRAL;
            int exitStrength = 0;

            if (!exitConfigs.isEmpty()) {
                try {
                    List<IndicatorResult> exitResults = indicatorEngine.computeAll(strategy, exitConfigs, barProvider);
                    if (IndicatorCalculationEngine.hasEnoughData(exitResults)) {
                        Signal exitSignal = signalGenerator.evaluate(strategy, exitConfigs, exitResults, primaryKlines);
                        exitSignalType = exitSignal.getSignalType();
                        exitStrength = exitSignal.getSignalStrength() != null ? exitSignal.getSignalStrength() : 0;
                    }
                } catch (Exception e) {
                    log.error("Exit indicator evaluation failed for strategy [{}]: {}",
                            strategy.getName(), e.getMessage(), e);
                }
            }

            try {
                tradeExecutionListener.onExitConditionCheck(strategy, exitSignalType, exitStrength);
            } catch (Exception e) {
                log.error("Exit condition check failed for strategy [{}]: {}",
                        strategy.getName(), e.getMessage(), e);
            }
        }

        // ── NEUTRAL 信号时的补充平仓判据：入场指标反向占比 ≥ 阈值即平仓 ──────────
        // 仅在 signal 为 NEUTRAL、策略配了 exitOnOppositeVoteRatio、且投票统计非空时触发。
        // 反向自动关仓（signal 为 SELL/BUY 时）已有独立路径，这里补的是"立场悄悄崩坏
        // 但没到反向"的盲区（例如 2 BUY + 1 SELL + 1 NEUTRAL，signal 是 NEUTRAL）。
        if (tradeExecutionListener != null
                && signal.getSignalType() == SignalType.NEUTRAL
                && strategy.getExitOnOppositeVoteRatio() != null
                && signal.getVoteBreakdown() != null
                && signal.getVoteBreakdown().total() > 0) {
            try {
                Signal.VoteBreakdown vb = signal.getVoteBreakdown();
                tradeExecutionListener.onNeutralVoteExitCheck(strategy,
                        vb.buyCount(), vb.sellCount(), vb.neutralCount());
            } catch (Exception e) {
                log.error("Neutral-vote exit check failed for strategy [{}]: {}",
                        strategy.getName(), e.getMessage(), e);
            }
        }

        // 跳过 NEUTRAL 信号，不写库不推送
//        if (signal.getSignalType() == SignalType.NEUTRAL) {
//            return;
//        }
        // 信号价格兜底：若指标未输出价格（null 或 0），回退到当前最新收盘价
        // 双重判空：signal.getPrice() 可能为 null；klineStore.getLatest() / latest.getClose() 也可能为 null
        if (signal.getPrice() == null || signal.getPrice().compareTo(BigDecimal.ZERO) == 0) {
            KLine latest = klineStore.getLatest(strategy.getExchange(), strategy.getSymbol(), strategy.getInterval());
            if (latest != null && latest.getClose() != null) {
                signal.setPrice(latest.getClose());
            } else {
                log.warn("Strategy [{}] cannot resolve signal price: no latest K-line data for {} {}",
                        strategy.getName(), strategy.getExchange(), strategy.getSymbol());
            }
        }

        // 幂等检查：同一策略同一K线时间相同信号类型只保存一次，
        // 防止同一K线收盘事件被多个发布路径触发导致重复写入
        boolean exists = signalMapper.selectCount(
                new LambdaQueryWrapper<Signal>()
                        .eq(Signal::getStrategyId, signal.getStrategyId())
                        .eq(Signal::getSignalTime, signal.getSignalTime())
                        .eq(Signal::getSignalType, signal.getSignalType())
        ) > 0;
        if (exists) {
            log.debug("Signal already exists, skip: strategy={} time={} type={}",
                    strategy.getName(), signal.getSignalTime(), signal.getSignalType());
            return;
        }

        // 数据权限：信号继承策略的创建者，便于行级数据隔离
        if (signal.getCreateBy() == null && strategy.getCreateBy() != null) {
            signal.setCreateBy(strategy.getCreateBy());
        }

        // 双写：MySQL + RocksDB
        signalMapper.insert(signal);
        try {
            signalStore.save(signal);
        } catch (Exception e) {
            log.warn("Failed to save signal to RocksDB, MySQL insert succeeded: {}", e.getMessage());
        }

        // WebSocket 推送（可选）
        if (signalPushService != null) {
            try {
                signalPushService.pushSignal(signal);
            } catch (Exception e) {
                log.warn("Failed to push signal via WebSocket: {}", e.getMessage());
            }
        }

        // 信号通知（分发到所有已激活渠道，内部异常隔离）
        // 跳过 NEUTRAL 信号，不推送
        if (signal.getSignalType() == SignalType.NEUTRAL) {
            return;
        }
        compositeSignalNotifier.notifySignal(signal);

        log.info("Strategy [{}] generated signal: {} (strength: {}) for {} {}",
                strategy.getName(), signal.getSignalType(), signal.getSignalStrength(),
                strategy.getExchange(), strategy.getSymbol());

        // 异步 AI 分析（仅方向性信号）：完全脱离主交易路径，
        // 失败/慢/未启用 都不影响信号入库与推送（aiAnalysisService 软依赖）。
        if (aiAnalysisService != null && aiAnalysisService.isEnabled()
                && signal.getSignalType() != SignalType.NEUTRAL) {
            try {
                aiAnalysisService.analyzeSignalAsync(strategy, signal);
            } catch (Exception e) {
                log.warn("[AI] submit signal analysis failed for strategy [{}]: {}",
                        strategy.getName(), e.getMessage());
            }
        }

        // 交易执行钩子（可选）
        if (tradeExecutionListener != null && strategy.getAutoTrade() != null
                && strategy.getAutoTrade() == 1
                && meetsStrengthThreshold(strategy, signal)) {
            try {
                tradeExecutionListener.onSignal(strategy, signal);
            } catch (Exception e) {
                log.error("Trade execution failed for strategy [{}]: {}",
                        strategy.getName(), e.getMessage(), e);
            }
        }
    }

    // ─── 辅助方法 ───────────────────────────────────────────────

    /**
     * 检查信号强度是否达到策略配置的最低门槛。
     * <ul>
     *   <li>策略未配置 minSignalStrength 时，默认门槛为 60</li>
     *   <li>信号强度低于门槛时，记录日志并返回 false，跳过本次委托</li>
     * </ul>
     */
    private static final int DEFAULT_MIN_SIGNAL_STRENGTH = 60;

    private boolean meetsStrengthThreshold(Strategy strategy, Signal signal) {
        int threshold = (strategy.getMinSignalStrength() != null && strategy.getMinSignalStrength() > 0)
                ? strategy.getMinSignalStrength()
                : DEFAULT_MIN_SIGNAL_STRENGTH;
        int strength = signal.getSignalStrength() != null ? signal.getSignalStrength() : 0;
        if (strength < threshold) {
            log.info("[{}] 信号强度 {} 低于门槛 {}，跳过自动交易（signal={}）",
                    strategy.getName(), strength, threshold, signal.getSignalType());
            return false;
        }
        return true;
    }

    /** 解析入场指标配置 JSON */
    private List<StrategyIndicatorConfig> parseConfigs(Strategy strategy) {
        return JSON.parseArray(strategy.getIndicatorConfigs(), StrategyIndicatorConfig.class);
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

    /** 获取指标的有效周期（有自定义则用自定义，否则用策略默认） */
    private KLineInterval getEffectiveInterval(StrategyIndicatorConfig config, Strategy strategy) {
        return config.getInterval() != null ? config.getInterval() : strategy.getInterval();
    }

    /** 收集策略所有用到的周期（去重） */
    private Set<KLineInterval> collectAllIntervals(Strategy strategy, List<StrategyIndicatorConfig> configs) {
        Set<KLineInterval> intervals = new HashSet<>();
        intervals.add(strategy.getInterval()); // 始终包含默认周期
        if (configs != null) {
            for (StrategyIndicatorConfig c : configs) {
                intervals.add(getEffectiveInterval(c, strategy));
            }
        }
        return intervals;
    }

    // ─── ATR 计算（复用 AtrIndicator 算法，避免重复依赖 KLineStore） ────────────

    /**
     * 从已加载的 K 线列表计算 ATR（Wilder 平滑法）。
     * <p>
     * 与 TradeExecutionService.computeAtr() 算法完全一致，
     * 避免在移动止损更新时重复查询 KLineStore。
     * </p>
     *
     * @param klines 升序K线列表（已预热，size >= period+1）
     * @param period ATR 周期（通常为 14）
     * @return ATR 绝对值，数据不足时返回 null
     */
    private BigDecimal computeAtrFromKlines(List<KLine> klines, int period) {
        if (klines == null || klines.size() < 2) return null;
        double[] trueRanges = new double[klines.size() - 1];
        for (int i = 1; i < klines.size(); i++) {
            KLine cur  = klines.get(i);
            KLine prev = klines.get(i - 1);
            double highLow   = cur.getHigh().doubleValue()  - cur.getLow().doubleValue();
            double highClose = Math.abs(cur.getHigh().doubleValue() - prev.getClose().doubleValue());
            double lowClose  = Math.abs(cur.getLow().doubleValue()  - prev.getClose().doubleValue());
            trueRanges[i - 1] = Math.max(highLow, Math.max(highClose, lowClose));
        }
        int calcPeriod = Math.min(period, trueRanges.length);
        double atr = 0;
        for (int i = 0; i < calcPeriod; i++) atr += trueRanges[i];
        atr /= calcPeriod;
        for (int i = calcPeriod; i < trueRanges.length; i++) {
            atr = (atr * (period - 1) + trueRanges[i]) / period;
        }
        return BigDecimal.valueOf(atr);
    }

    // ─── 节流 ─────────────────────────────────────────────────────

    /**
     * 检查策略是否在节流冷却期内
     * <p>
     * 如果距上次执行时间不足 minEvalIntervalMs，返回 true（应跳过）。
     * 否则更新时间戳并返回 false（允许执行）。
     * </p>
     */
    private boolean isThrottled(Long strategyId) {
        long minInterval = properties.getEngine().getMinEvalIntervalMs();
        if (minInterval <= 0) {
            return false;
        }
        long now = System.currentTimeMillis();
        // 使用 compute() 原子完成"读-判断-写"，避免并发线程同时通过节流检查
        boolean[] throttled = {false};
        lastEvalTimeMap.compute(strategyId, (k, lastTime) -> {
            if (lastTime != null && (now - lastTime) < minInterval) {
                throttled[0] = true;
                return lastTime; // 不更新时间戳
            }
            return now; // 允许执行，更新时间戳
        });
        return throttled[0];
    }
}

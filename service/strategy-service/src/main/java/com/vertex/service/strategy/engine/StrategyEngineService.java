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
import com.vertex.service.strategy.indicator.IndicatorRegistry;
import com.vertex.service.strategy.indicator.TechnicalIndicator;
import com.vertex.service.strategy.mapper.SignalMapper;
import com.vertex.service.strategy.mapper.StrategyMapper;
import com.vertex.service.strategy.store.SignalStore;
import com.vertex.api.trading.ITradeExecutionListener;
import com.vertex.service.strategy.websocket.SignalPushService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 策略引擎服务（编排器）
 * <p>
 * 负责：加载策略 → 获取K线 → 计算指标 → 生成信号 → 双写存储 → WebSocket推送
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
    private final IndicatorRegistry indicatorRegistry;
    private final StrategyProperties properties;

    /** 信号推送服务（可选依赖，WebSocket 未配置时为 null） */
    @Autowired(required = false)
    private SignalPushService signalPushService;

    /** 交易执行监听器（可选依赖，trading 未启用时为 null） */
    @Autowired(required = false)
    private ITradeExecutionListener tradeExecutionListener;

    /** 节流：记录每个策略的上次执行时间戳（毫秒） */
    private final ConcurrentHashMap<Long, Long> lastEvalTimeMap = new ConcurrentHashMap<>();

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
        List<StrategyIndicatorConfig> configs = parseConfigs(strategy);
        if (configs == null || configs.isEmpty()) {
            log.warn("Strategy [{}] has no indicator configs", strategy.getName());
            return;
        }

        // ── 第一步：预计算每个周期所有指标的最大 requiredDataPoints ──────────────────
        // 与 BacktestService 保持一致（BacktestService 使用 requiredByInterval.merge(..., Math::max)）。
        // 若某周期有多个指标（如 RSI+MACD），必须用最大值来确定窗口，否则窗口过小
        // 导致 EMA 预热不足，与回测的滑动窗口产生偏差，进而出现信号不一致。
        Map<KLineInterval, Integer> maxRequiredByInterval = new HashMap<>();
        for (StrategyIndicatorConfig config : configs) {
            KLineInterval iv = getEffectiveInterval(config, strategy);
            TechnicalIndicator ind = indicatorRegistry.get(config.getIndicatorType());
            int req = ind.requiredDataPoints(config.getParams());
            maxRequiredByInterval.merge(iv, req, Math::max);
        }

        // 按周期分组获取 K线
        Map<KLineInterval, List<KLine>> klinesByInterval = new HashMap<>();
        boolean hasEnoughData = false;

        for (StrategyIndicatorConfig config : configs) {
            KLineInterval effectiveInterval = getEffectiveInterval(config, strategy);
            if (!klinesByInterval.containsKey(effectiveInterval)) {
                // 使用该周期所有指标中最大的 required（与回测对齐），而非仅当前指标的 required
                int required = maxRequiredByInterval.getOrDefault(effectiveInterval, 50);
                // 使用 warmupMultiplier 倍数多取历史K线，让 EMA/KDJ 等状态型指标充分预热，
                // 避免因历史锚点不同导致与回测信号不一致
                int warmup = properties.getEngine().getWarmupMultiplier();
                int fetchSize = Math.min(required * warmup + 10, properties.getEngine().getMaxKlineHistory());

                // 以触发事件的K线 openTime 为查询上界（endTime），锁定窗口右端。
                // 这样无论 @Async 线程何时处理事件，都能保证窗口结束于正确的K线，
                // 与回测的滑动窗口完全对齐，消除信号时间漂移。
                // triggeringKlineTime=null（手动触发）时不限上界，取当前最新数据。
                int querySize = properties.getEngine().isOnlyClosedKlines() ? fetchSize + 1 : fetchSize;
                List<KLine> klines = klineStore.query(
                        strategy.getExchange(), strategy.getSymbol(),
                        effectiveInterval, null, triggeringKlineTime, querySize, false);

                // 过滤未收盘K线，确保指标计算只使用已收盘数据（与回测一致）
                if (properties.getEngine().isOnlyClosedKlines()) {
                    klines = klines.stream()
                            .filter(k -> Boolean.TRUE.equals(k.getClosed()))
                            .toList();
                }

                // 截断到 fetchSize 根（保留降序头部=最新），与回测滑动窗口对齐。
                if (klines.size() > fetchSize) {
                    klines = klines.subList(0, fetchSize);
                }

                // 降序查询结果翻转为升序（时间从早到晚）
                klines = new ArrayList<>(klines);
                Collections.reverse(klines);

                // 调试：记录实盘窗口范围，便于与回测滑动窗口核对
                if (log.isDebugEnabled() && !klines.isEmpty()) {
                    log.debug("[Signal] strategy='{}' interval={} window=[{} → {}] size={} fetchSize={}",
                            strategy.getName(), effectiveInterval.getCode(),
                            klines.get(0).getOpenTime(),
                            klines.get(klines.size() - 1).getOpenTime(),
                            klines.size(), fetchSize);
                }

                if (klines.size() < required) {
                    log.debug("Insufficient data for interval {} in strategy '{}' ({}/{})",
                            effectiveInterval, strategy.getName(), klines.size(), required);
                } else {
                    hasEnoughData = true;
                }
                klinesByInterval.put(effectiveInterval, klines);
            } else {
                hasEnoughData = true; // 之前已放入且不为空
            }
        }

        if (!hasEnoughData) {
            log.debug("Strategy [{}] skipped: no interval has sufficient K-line data", strategy.getName());
            return;
        }

        // 生成信号
        Signal signal = signalGenerator.evaluate(strategy, configs, klinesByInterval);

        // 跳过 NEUTRAL 信号，不写库不推送
        if (signal.getSignalType() == SignalType.NEUTRAL) {
            return;
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

        log.info("Strategy [{}] generated signal: {} (strength: {}) for {} {}",
                strategy.getName(), signal.getSignalType(), signal.getSignalStrength(),
                strategy.getExchange(), strategy.getSymbol());

        // 交易执行钩子（可选）
        if (tradeExecutionListener != null && strategy.getAutoTrade() != null
                && strategy.getAutoTrade() == 1) {
            try {
                tradeExecutionListener.onSignal(strategy, signal);
            } catch (Exception e) {
                log.error("Trade execution failed for strategy [{}]: {}",
                        strategy.getName(), e.getMessage(), e);
            }
        }
    }

    // ─── 辅助方法 ───────────────────────────────────────────────

    /** 解析指标配置 JSON */
    private List<StrategyIndicatorConfig> parseConfigs(Strategy strategy) {
        return JSON.parseArray(strategy.getIndicatorConfigs(), StrategyIndicatorConfig.class);
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
        Long lastTime = lastEvalTimeMap.get(strategyId);
        if (lastTime != null && (now - lastTime) < minInterval) {
            return true;
        }
        lastEvalTimeMap.put(strategyId, now);
        return false;
    }
}

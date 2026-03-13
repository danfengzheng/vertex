package com.vertex.service.strategy.engine;

import com.vertex.model.dto.strategy.StrategyIndicatorConfig;
import com.vertex.model.entity.quote.KLine;
import com.vertex.model.entity.quote.KLineInterval;
import com.vertex.model.entity.strategy.Strategy;
import com.vertex.service.strategy.config.StrategyProperties;
import com.vertex.service.strategy.indicator.IndicatorRegistry;
import com.vertex.service.strategy.indicator.IndicatorResult;
import com.vertex.service.strategy.indicator.TechnicalIndicator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 指标计算引擎
 *
 * <h3>职责</h3>
 * <ol>
 *   <li>按周期分组，统计各周期所需最大 requiredDataPoints（含 warmupMultiplier 预热）</li>
 *   <li>每个周期只通过 {@link BarProvider} 取一次 K 线，同周期的所有指标共享同一窗口</li>
 *   <li>为高于主周期的二级周期构建 PartialBar（从主周期 bars 聚合，消除 Lookahead Bias）</li>
 *   <li>调用各指标 {@code calculate()}，汇总计算结果</li>
 * </ol>
 *
 * <h3>设计原则</h3>
 * <ul>
 *   <li>指标只做计算，不做判断（BUY/SELL/NEUTRAL 由策略层决定）</li>
 *   <li>通过 {@link BarProvider} 接口解耦数据来源：实盘注入 {@link LiveBarProvider}，
 *       回测注入 {@link com.vertex.service.strategy.backtest.BacktestBarProvider}</li>
 *   <li>未来行情模块增加内存 bar 缓存，只需修改 LiveBarProvider，引擎无需改动</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IndicatorCalculationEngine {

    private final IndicatorRegistry indicatorRegistry;
    private final StrategyProperties properties;

    /**
     * 批量计算策略的所有指标，结果按 configs 下标对齐返回。
     * <p>
     * 返回列表与 configs 等长：results.get(i) 对应 configs.get(i)，
     * 计算失败或数据不足时该位置为 {@code null}。
     * </p>
     *
     * @param strategy    策略实体（提供主周期、exchange、symbol）
     * @param configs     指标配置列表（含硬性过滤器和投票指标）
     * @param barProvider bar 数据提供者（实盘 or 回测）
     * @return 按 configs 下标对齐的 IndicatorResult 列表
     */
    public List<IndicatorResult> computeAll(Strategy strategy,
                                            List<StrategyIndicatorConfig> configs,
                                            BarProvider barProvider) {

        // ── 步骤1：按周期统计最大 requiredDataPoints ─────────────────────────────
        Map<KLineInterval, Integer> maxRequired = new HashMap<>();
        for (StrategyIndicatorConfig config : configs) {
            KLineInterval iv = getEffectiveInterval(config, strategy);
            TechnicalIndicator indicator = indicatorRegistry.get(config.getIndicatorType());
            int req = indicator.requiredDataPoints(config.getParams());
            maxRequired.merge(iv, req, Math::max);
        }

        // ── 步骤2：每个周期取一次 bar（warmupMultiplier 确保 EMA/KDJ 等充分预热）─
        int warmup = properties.getEngine().getWarmupMultiplier();
        int maxHistory = properties.getEngine().getMaxKlineHistory();
        Map<KLineInterval, List<KLine>> klinesByInterval = new HashMap<>();

        for (Map.Entry<KLineInterval, Integer> entry : maxRequired.entrySet()) {
            KLineInterval iv = entry.getKey();
            int fetchSize = Math.min(entry.getValue() * warmup + 10, maxHistory);

            // 大周期二级指标：barProvider 返回 closeTime < triggerTime 的已收盘 bar，
            // 当前周期部分状态由 PartialBar 追加（步骤3），消除 Lookahead Bias
            List<KLine> bars = barProvider.getBars(iv, fetchSize);
            klinesByInterval.put(iv, bars);

            if (log.isDebugEnabled() && !bars.isEmpty()) {
                log.debug("[IndEngine] strategy='{}' interval={} window=[{} → {}] size={}",
                        strategy.getName(), iv.getCode(),
                        bars.get(0).getOpenTime(),
                        bars.get(bars.size() - 1).getOpenTime(),
                        bars.size());
            }
        }

        // ── 步骤3：为大周期追加 PartialBar（用主周期已收盘 bars 实时聚合）────────
        List<KLine> primaryBars = klinesByInterval.get(strategy.getInterval());
        if (primaryBars != null && !primaryBars.isEmpty()) {
            long triggerTime = barProvider.getTriggerTime() != null
                    ? barProvider.getTriggerTime()
                    : primaryBars.get(primaryBars.size() - 1).getOpenTime();

            for (KLineInterval iv : new HashSet<>(klinesByInterval.keySet())) {
                if (iv.equals(strategy.getInterval())) continue;
                if (iv.getMillis() <= strategy.getInterval().getMillis()) continue;

                KLine partialBar = KLinePartialBarBuilder.buildPartialBar(
                        primaryBars, iv, triggerTime,
                        strategy.getExchange(), strategy.getSymbol());

                if (partialBar != null) {
                    List<KLine> withPartial = new ArrayList<>(klinesByInterval.get(iv));
                    withPartial.add(partialBar);
                    klinesByInterval.put(iv, withPartial);
                    log.debug("[IndEngine] partial {} bar appended: openTime={}", iv.getCode(), partialBar.getOpenTime());
                }
            }
        }

        // ── 步骤4：按 configs 顺序调用各指标计算，返回对齐结果列表 ─────────────────
        List<IndicatorResult> results = new ArrayList<>(configs.size());
        for (StrategyIndicatorConfig config : configs) {
            KLineInterval iv = getEffectiveInterval(config, strategy);
            List<KLine> bars = klinesByInterval.get(iv);

            if (bars == null || bars.isEmpty()) {
                log.debug("[IndEngine] no bars for {} interval={} strategy='{}'",
                        config.getIndicatorType(), iv.getCode(), strategy.getName());
                results.add(null);
                continue;
            }

            try {
                TechnicalIndicator indicator = indicatorRegistry.get(config.getIndicatorType());
                IndicatorResult result = indicator.calculate(bars, config.getParams());
                results.add(result);
            } catch (Exception e) {
                log.warn("[IndEngine] {} calculation failed for strategy '{}': {}",
                        config.getIndicatorType(), strategy.getName(), e.getMessage());
                results.add(null);
            }
        }

        return results;
    }

    /**
     * 计算各周期所需最大 requiredDataPoints（供回测预取区间计算使用）。
     * <p>
     * 与 computeAll() 步骤1相同，单独暴露以供 BacktestService 计算 prefetchStart。
     * </p>
     */
    public Map<KLineInterval, Integer> computeMaxRequired(Strategy strategy,
                                                          List<StrategyIndicatorConfig> configs) {
        Map<KLineInterval, Integer> maxRequired = new HashMap<>();
        for (StrategyIndicatorConfig config : configs) {
            KLineInterval iv = getEffectiveInterval(config, strategy);
            TechnicalIndicator indicator = indicatorRegistry.get(config.getIndicatorType());
            int req = indicator.requiredDataPoints(config.getParams());
            maxRequired.merge(iv, req, Math::max);
        }
        return maxRequired;
    }

    /**
     * 判断策略是否有足够的 bar 数据（至少一个周期满足 requiredDataPoints）。
     * StrategyEngineService 可在 computeAll 之后检查结果列表是否全为 null。
     */
    public static boolean hasEnoughData(List<IndicatorResult> results) {
        return results != null && results.stream().anyMatch(Objects::nonNull);
    }

    private KLineInterval getEffectiveInterval(StrategyIndicatorConfig config, Strategy strategy) {
        return config.getInterval() != null ? config.getInterval() : strategy.getInterval();
    }
}

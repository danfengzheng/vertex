package com.vertex.service.strategy.engine;

import com.vertex.model.dto.strategy.FilterCondition;
import com.vertex.model.dto.strategy.StrategyIndicatorConfig;
import com.vertex.model.entity.quote.KLine;
import com.vertex.model.entity.quote.KLineInterval;
import com.vertex.model.entity.strategy.Signal;
import com.vertex.model.entity.strategy.SignalType;
import com.vertex.model.entity.strategy.Strategy;
import com.vertex.service.strategy.indicator.IndicatorRegistry;
import com.vertex.service.strategy.indicator.IndicatorResult;
import com.vertex.service.strategy.indicator.TechnicalIndicator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 信号生成器
 * <p>
 * 三阶段流程：
 * <ol>
 *   <li><b>前置硬性过滤器（Pre-Filter）</b>：先于复合投票独立校验。
 *       <ul>
 *         <li>数值校验模式（filterConditions 非空）：逐条检查阈值，任一失败则立即返回 NEUTRAL，跳过复合计算。</li>
 *         <li>方向校验模式（filterConditions 为空）：指标必须返回非 NEUTRAL 信号，否则立即返回 NEUTRAL。</li>
 *       </ul>
 *   </li>
 *   <li><b>复合投票</b>：仅投票指标参与三桶加权评分，确定 BUY / SELL / NEUTRAL。</li>
 *   <li><b>后置方向对齐（Post-Filter，仅方向模式）</b>：复合信号方向必须与过滤器方向一致，不一致则否决为 NEUTRAL。</li>
 * </ol>
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SignalGenerator {

    private final IndicatorRegistry indicatorRegistry;

    /**
     * 评估策略，生成信号（支持多周期K线）
     *
     * @param strategy         策略实体
     * @param configs          指标配置列表
     * @param klinesByInterval 按周期分组的时间升序K线数据
     * @return Signal 实体（未设置 id）
     */
    public Signal evaluate(Strategy strategy, List<StrategyIndicatorConfig> configs,
                           Map<KLineInterval, List<KLine>> klinesByInterval) {
        Map<String, Object> allIndicatorValues = new HashMap<>();
        StringBuilder descBuilder = new StringBuilder();

        // ── 分离硬性过滤器与投票指标 ─────────────────────────────────────────────
        List<StrategyIndicatorConfig> filterConfigs = new ArrayList<>();
        List<StrategyIndicatorConfig> votingConfigs = new ArrayList<>();
        for (StrategyIndicatorConfig c : configs) {
            if (Boolean.TRUE.equals(c.getHardFilter())) {
                filterConfigs.add(c);
            } else {
                votingConfigs.add(c);
            }
        }

        // ════════════════════════════════════════════════════════════════════════
        // 阶段一：前置硬性过滤器校验（先于复合投票；不满足则直接返回 NEUTRAL）
        // ════════════════════════════════════════════════════════════════════════
        // 缓存过滤器计算结果，供方向模式在阶段三复用（避免重复计算）
        List<IndicatorResult> filterResultCache = new ArrayList<>();

        for (StrategyIndicatorConfig fc : filterConfigs) {
            TechnicalIndicator indicator = indicatorRegistry.get(fc.getIndicatorType());
            KLineInterval effectiveInterval = fc.getInterval() != null
                    ? fc.getInterval() : strategy.getInterval();
            List<KLine> klines = klinesByInterval.getOrDefault(effectiveInterval, List.of());
            String label = buildFilterLabel(fc, strategy);

            // ── 无数据：立即否决 ─────────────────────────────────────────────
            if (klines.isEmpty()) {
                log.info("[HardFilter] vetoed by {}: no kline data", label);
                descBuilder.append(label).append("[FILTER:no_data] ");
                return buildSignal(strategy, klinesByInterval, allIndicatorValues,
                        SignalType.NEUTRAL, 0, descBuilder);
            }

            try {
                IndicatorResult filterResult = indicator.calculate(klines, fc.getParams());
                allIndicatorValues.putAll(filterResult.getValues());
                filterResultCache.add(filterResult); // 供阶段三使用

                List<FilterCondition> conditions = fc.getFilterConditions();
                if (conditions != null && !conditions.isEmpty()) {
                    // ── 数值校验模式 ─────────────────────────────────────────
                    StringBuilder condDesc = new StringBuilder(label).append("[FILTER]");
                    boolean allPass = true;
                    for (FilterCondition cond : conditions) {
                        Double actual = filterResult.getValues().get(cond.getField());
                        String expr = cond.getField() + opSymbol(cond.getOp()) + cond.getThreshold();
                        if (actual == null || !evalCondition(actual, cond.getOp(), cond.getThreshold())) {
                            log.info("[HardFilter] vetoed by {}: condition '{}' failed (actual={})",
                                    label, expr, actual);
                            condDesc.append("(FAIL:").append(expr).append(")");
                            allPass = false;
                            break;
                        }
                        condDesc.append("(OK:").append(expr).append(")");
                    }
                    descBuilder.append(condDesc).append(" ");
                    if (!allPass) {
                        return buildSignal(strategy, klinesByInterval, allIndicatorValues,
                                SignalType.NEUTRAL, 0, descBuilder);
                    }
                } else {
                    // ── 方向校验模式（预检）：指标不得为 NEUTRAL ─────────────
                    // 预检只验证"有明确方向"；方向对齐留到阶段三
                    descBuilder.append(label).append("[FILTER]=")
                            .append(filterResult.getSuggestion()).append(" ");
                    if (filterResult.getSuggestion() == IndicatorResult.SignalSuggestion.NEUTRAL) {
                        log.info("[HardFilter] vetoed by {}: indicator is NEUTRAL (no clear direction)", label);
                        descBuilder.append("(VETOED:NEUTRAL) ");
                        return buildSignal(strategy, klinesByInterval, allIndicatorValues,
                                SignalType.NEUTRAL, 0, descBuilder);
                    }
                }

            } catch (Exception e) {
                log.warn("[HardFilter] {} calculation failed, vetoing: {}", label, e.getMessage());
                descBuilder.append(label).append("[FILTER:error] ");
                return buildSignal(strategy, klinesByInterval, allIndicatorValues,
                        SignalType.NEUTRAL, 0, descBuilder);
            }
        }

        // 所有硬性过滤器均通过，分隔符
        if (!filterConfigs.isEmpty()) {
            descBuilder.append("| ");
        }

        // ════════════════════════════════════════════════════════════════════════
        // 阶段二：投票指标三桶加权评分
        // ════════════════════════════════════════════════════════════════════════
        int buyWeight = 0, sellWeight = 0, neutralWeight = 0;

        for (StrategyIndicatorConfig config : votingConfigs) {
            TechnicalIndicator indicator = indicatorRegistry.get(config.getIndicatorType());
            int weight = config.getWeight() != null ? config.getWeight() : 50;
            KLineInterval effectiveInterval = config.getInterval() != null
                    ? config.getInterval() : strategy.getInterval();
            List<KLine> klines = klinesByInterval.getOrDefault(effectiveInterval, List.of());

            if (klines.isEmpty()) {
                neutralWeight += weight;
                descBuilder.append(config.getIndicatorType().getCode())
                        .append("=NEUTRAL(no_data,w:").append(weight).append(") ");
                continue;
            }

            try {
                IndicatorResult result = indicator.calculate(klines, config.getParams());
                allIndicatorValues.putAll(result.getValues());
                switch (result.getSuggestion()) {
                    case BUY     -> buyWeight     += weight;
                    case SELL    -> sellWeight    += weight;
                    case NEUTRAL -> neutralWeight += weight;
                }
                descBuilder.append(config.getIndicatorType().getCode());
                if (config.getInterval() != null && config.getInterval() != strategy.getInterval()) {
                    descBuilder.append("[").append(config.getInterval().getCode()).append("]");
                }
                descBuilder.append("=").append(result.getSuggestion())
                        .append("(w:").append(weight).append(") ");
            } catch (Exception e) {
                log.warn("Indicator {} calculation failed for strategy {}: {}",
                        config.getIndicatorType(), strategy.getName(), e.getMessage());
                neutralWeight += weight;
            }
        }

        // ── 确定复合信号 ─────────────────────────────────────────────────────────
        int totalWeight = buyWeight + sellWeight + neutralWeight;
        SignalType signalType;
        int strength;
        if (totalWeight == 0) {
            signalType = SignalType.NEUTRAL;
            strength = 0;
        } else if (buyWeight > sellWeight && buyWeight > neutralWeight) {
            signalType = SignalType.BUY;
            strength = (int) Math.round((double) buyWeight / totalWeight * 100);
        } else if (sellWeight > buyWeight && sellWeight > neutralWeight) {
            signalType = SignalType.SELL;
            strength = (int) Math.round((double) sellWeight / totalWeight * 100);
        } else {
            signalType = SignalType.NEUTRAL;
            strength = (int) Math.round((double) neutralWeight / totalWeight * 100);
        }

        // ════════════════════════════════════════════════════════════════════════
        // 阶段三：后置方向对齐（仅方向模式过滤器 + BUY/SELL 复合信号）
        // ════════════════════════════════════════════════════════════════════════
        if (signalType != SignalType.NEUTRAL) {
            for (int i = 0; i < filterConfigs.size(); i++) {
                StrategyIndicatorConfig fc = filterConfigs.get(i);
                List<FilterCondition> conditions = fc.getFilterConditions();
                // 数值模式已在阶段一处理，跳过
                if (conditions != null && !conditions.isEmpty()) continue;
                if (i >= filterResultCache.size()) continue; // 安全保护

                IndicatorResult cached = filterResultCache.get(i);
                String label = buildFilterLabel(fc, strategy);

                boolean directionMatch =
                        (signalType == SignalType.BUY  && cached.getSuggestion() == IndicatorResult.SignalSuggestion.BUY)
                     || (signalType == SignalType.SELL && cached.getSuggestion() == IndicatorResult.SignalSuggestion.SELL);

                if (!directionMatch) {
                    log.info("[HardFilter] {} direction mismatch by {}: filter={} composite={}",
                            signalType, label, cached.getSuggestion(), signalType);
                    descBuilder.append("| ").append(label)
                            .append("[DIR-MISMATCH:").append(cached.getSuggestion()).append("→VETOED] ");
                    signalType = SignalType.NEUTRAL;
                    strength = 0;
                    break;
                }
            }
        }

        return buildSignal(strategy, klinesByInterval, allIndicatorValues, signalType, strength, descBuilder);
    }

    // ── 私有工具方法 ──────────────────────────────────────────────────────────

    private Signal buildSignal(Strategy strategy, Map<KLineInterval, List<KLine>> klinesByInterval,
                               Map<String, Object> indicatorValues, SignalType signalType, int strength,
                               StringBuilder descBuilder) {
        List<KLine> defaultKlines = klinesByInterval.getOrDefault(strategy.getInterval(), List.of());
        BigDecimal price = BigDecimal.ZERO;
        long signalTime = System.currentTimeMillis();
        if (!defaultKlines.isEmpty()) {
            KLine lastKline = defaultKlines.get(defaultKlines.size() - 1);
            price = lastKline.getClose();
            signalTime = lastKline.getOpenTime();
        }
        Signal signal = new Signal();
        signal.setStrategyId(strategy.getId());
        signal.setStrategyName(strategy.getName());
        signal.setSymbol(strategy.getSymbol());
        signal.setExchange(strategy.getExchange());
        signal.setInterval(strategy.getInterval());
        signal.setSignalType(signalType);
        signal.setSignalStrength(strength);
        signal.setPrice(price);
        signal.setSignalTime(signalTime);
        signal.setIndicators(com.alibaba.fastjson2.JSON.toJSONString(indicatorValues));
        signal.setDescription(descBuilder.toString().trim());
        return signal;
    }

    private String buildFilterLabel(StrategyIndicatorConfig fc, Strategy strategy) {
        String label = fc.getIndicatorType().getCode();
        if (fc.getInterval() != null && fc.getInterval() != strategy.getInterval()) {
            label += "[" + fc.getInterval().getCode() + "]";
        }
        return label;
    }

    /** 数值条件校验：将指标计算值与阈值按运算符比较 */
    private boolean evalCondition(double actual, String op, double threshold) {
        return switch (op) {
            case "GT"  -> actual > threshold;
            case "GTE" -> actual >= threshold;
            case "LT"  -> actual < threshold;
            case "LTE" -> actual <= threshold;
            case "EQ"  -> Math.abs(actual - threshold) < 1e-9;
            default    -> {
                log.warn("[HardFilter] Unknown operator: {}", op);
                yield false;
            }
        };
    }

    /** 将运算符代码转换为可读符号（用于描述） */
    private String opSymbol(String op) {
        return switch (op) {
            case "GT"  -> ">";
            case "GTE" -> ">=";
            case "LT"  -> "<";
            case "LTE" -> "<=";
            case "EQ"  -> "=";
            default    -> op;
        };
    }
}

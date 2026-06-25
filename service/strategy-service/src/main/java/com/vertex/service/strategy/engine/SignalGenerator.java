package com.vertex.service.strategy.engine;

import com.vertex.model.dto.strategy.FilterCondition;
import com.vertex.model.dto.strategy.StrategyIndicatorConfig;
import com.vertex.model.entity.quote.KLine;
import com.vertex.model.entity.quote.KLineInterval;
import com.vertex.model.entity.strategy.Signal;
import com.vertex.model.entity.strategy.SignalType;
import com.vertex.model.entity.strategy.Strategy;
import com.vertex.service.strategy.indicator.IndicatorResult;
import com.vertex.service.strategy.indicator.IndicatorResult.SignalSuggestion;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.*;

/**
 * 信号生成器
 *
 * <h3>三阶段执行流程</h3>
 * <ol>
 *   <li><b>阶段一：前置硬性过滤器（Pre-Filter）</b> — 先于复合投票执行。
 *     <ul>
 *       <li>方向校验模式（filterConditions 为空）：指标必须返回非 NEUTRAL，否则立即返回 NEUTRAL。</li>
 *       <li>数值校验模式（filterConditions 非空）：仅校验 {@code applyTo=null}（双向通用）的条件，
 *           任一失败则跳过复合计算直接返回 NEUTRAL。</li>
 *     </ul>
 *   </li>
 *   <li><b>阶段二：复合投票</b> — 仅投票指标参与三桶加权评分，确定 BUY / SELL / NEUTRAL。
 *     <ul>
 *       <li>新方式（推荐）：指标配置了 {@code buyConditions}/{@code sellConditions} 时，
 *           按策略层定义的条件对 allValues 求值，全部满足则投对应方向票。</li>
 *       <li>旧方式（向后兼容）：未配置条件时，回退到指标内部的 suggestion 结果。</li>
 *     </ul>
 *   </li>
 *   <li><b>阶段三：后置方向校验（Post-Filter）</b> — 在已知复合信号方向后执行。
 *     <ul>
 *       <li>方向校验模式：复合方向必须与过滤器方向一致，不一致则否决为 NEUTRAL。</li>
 *       <li>数值校验模式：校验 {@code applyTo="BUY"} 或 {@code applyTo="SELL"} 与复合方向匹配的条件，
 *           不满足则否决为 NEUTRAL。</li>
 *     </ul>
 *   </li>
 * </ol>
 *
 * <h3>applyTo 语义（硬性过滤器）</h3>
 * <ul>
 *   <li>{@code null}（双向通用）：阶段一校验，任一失败则阻断所有方向信号。</li>
 *   <li>{@code "BUY"} / {@code "SELL"}：阶段三校验，只影响对应方向信号。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SignalGenerator {

    /**
     * 评估策略，生成信号。
     * <p>
     * 由 {@link IndicatorCalculationEngine} 预先完成所有指标计算，
     * 本方法只负责三阶段信号判断逻辑，不再直接接触 K 线数据。
     * </p>
     *
     * @param strategy   策略实体
     * @param configs    指标配置列表（与 results 下标对齐）
     * @param results    各指标计算结果（与 configs 下标对齐，null 表示计算失败）
     * @param klines     主周期 K 线（仅用于构建 Signal 的 price/signalTime，不参与指标计算）
     * @return Signal 实体（未设置 id）
     */
    public Signal evaluate(Strategy strategy,
                           List<StrategyIndicatorConfig> configs,
                           List<IndicatorResult> results,
                           List<KLine> klines) {

        // 汇总所有指标的 values，供条件判断和 Signal.indicators 字段使用
        Map<String, Double> allValues = new HashMap<>();
        for (IndicatorResult r : results) {
            if (r != null) allValues.putAll(r.getValues());
        }

        StringBuilder descBuilder = new StringBuilder();

        // ── 分离硬性过滤器与投票指标 ─────────────────────────────────────────────
        List<Integer> filterIdxList = new ArrayList<>();
        List<Integer> votingIdxList = new ArrayList<>();
        for (int i = 0; i < configs.size(); i++) {
            if (Boolean.TRUE.equals(configs.get(i).getHardFilter())) {
                filterIdxList.add(i);
            } else {
                votingIdxList.add(i);
            }
        }

        // ════════════════════════════════════════════════════════════════════════
        // 阶段一：前置硬性过滤器
        // ════════════════════════════════════════════════════════════════════════
        for (int idx : filterIdxList) {
            StrategyIndicatorConfig fc = configs.get(idx);
            IndicatorResult filterResult = results.get(idx);
            String label = fc.getIndicatorType().getCode()
                    + (fc.getInterval() != null ? "[" + fc.getInterval().getCode() + "]" : "");

            if (filterResult == null) {
                log.debug("[HardFilter] vetoed by {}: no data or calculation failed", label);
                descBuilder.append(label).append("[FILTER:no_data] ");
                return buildSignal(strategy, klines, allValues, SignalType.NEUTRAL, 0, descBuilder);
            }

            List<FilterCondition> conditions = fc.getFilterConditions();
            if (conditions != null && !conditions.isEmpty()) {
                // ── 数值校验模式：阶段一仅处理 applyTo=null（双向通用）的条件 ──
                StringBuilder condDesc = new StringBuilder(label).append("[FILTER]");
                boolean hasUniversal = false;
                boolean allPass = true;

                for (FilterCondition cond : conditions) {
                    if (cond.getApplyTo() != null) continue;
                    hasUniversal = true;

                    Double actual = filterResult.getValues().get(cond.getField());
                    String expr = cond.getField() + opSymbol(cond.getOp()) + cond.getThreshold();
                    if (actual == null || !evalCondition(actual, cond.getOp(), cond.getThreshold())) {
                        log.debug("[HardFilter] vetoed by {}: condition '{}' failed (actual={})", label, expr, actual);
                        condDesc.append("(FAIL:").append(expr).append(")");
                        allPass = false;
                        break;
                    }
                    condDesc.append("(OK:").append(expr).append(")");
                }

                if (hasUniversal) {
                    descBuilder.append(condDesc).append(" ");
                    if (!allPass) {
                        return buildSignal(strategy, klines, allValues, SignalType.NEUTRAL, 0, descBuilder);
                    }
                }
            } else {
                // ── 方向校验模式（预检）：指标不得为 NEUTRAL ─────────────────
                descBuilder.append(label).append("[FILTER]=")
                        .append(filterResult.getSuggestion()).append(" ");
                if (filterResult.getSuggestion() == SignalSuggestion.NEUTRAL) {
                    log.debug("[HardFilter] vetoed by {}: NEUTRAL direction", label);
                    descBuilder.append("(VETOED:NEUTRAL) ");
                    return buildSignal(strategy, klines, allValues, SignalType.NEUTRAL, 0, descBuilder);
                }
            }
        }

        if (!filterIdxList.isEmpty()) {
            descBuilder.append("| ");
        }

        // ════════════════════════════════════════════════════════════════════════
        // 阶段二：投票指标三桶加权评分
        // ════════════════════════════════════════════════════════════════════════
        int buyWeight = 0, sellWeight = 0, neutralWeight = 0;

        for (int idx : votingIdxList) {
            StrategyIndicatorConfig config = configs.get(idx);
            IndicatorResult result = results.get(idx);
            int weight = config.getWeight() != null ? config.getWeight() : 50;
            int penalty = config.getPenaltyWeight() != null ? config.getPenaltyWeight() : 0;

            if (result == null) {
                neutralWeight += weight;
                descBuilder.append(config.getIndicatorType().getCode())
                        .append("=NEUTRAL(no_data,w:").append(weight).append(") ");
                continue;
            }

            // 确定本指标的投票方向
            SignalSuggestion vote = resolveVote(config, result, allValues);

            switch (vote) {
                case BUY -> {
                    buyWeight  += weight;
                    sellWeight  = Math.max(0, sellWeight - penalty);
                }
                case SELL -> {
                    sellWeight += weight;
                    buyWeight   = Math.max(0, buyWeight - penalty);
                }
                case NEUTRAL -> {
                    neutralWeight += weight;
                    // NEUTRAL 时 penaltyWeight 压制当前主导方向
                    if (buyWeight > sellWeight) {
                        buyWeight = Math.max(0, buyWeight - penalty);
                    } else if (sellWeight > buyWeight) {
                        sellWeight = Math.max(0, sellWeight - penalty);
                    }
                }
            }

            descBuilder.append(config.getIndicatorType().getCode());
            if (config.getInterval() != null && config.getInterval() != strategy.getInterval()) {
                descBuilder.append("[").append(config.getInterval().getCode()).append("]");
            }
            descBuilder.append("=").append(vote)
                    .append("(w:").append(weight).append(") ");
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
        // 阶段三：后置方向校验
        // ════════════════════════════════════════════════════════════════════════
        if (signalType != SignalType.NEUTRAL) {
            for (int idx : filterIdxList) {
                StrategyIndicatorConfig fc = configs.get(idx);
                IndicatorResult cached = results.get(idx);
                if (cached == null) continue;

                String label = fc.getIndicatorType().getCode()
                        + (fc.getInterval() != null ? "[" + fc.getInterval().getCode() + "]" : "");
                List<FilterCondition> conditions = fc.getFilterConditions();

                if (conditions == null || conditions.isEmpty()) {
                    // 方向校验模式
                    boolean dirMatch =
                            (signalType == SignalType.BUY  && cached.getSuggestion() == SignalSuggestion.BUY)
                         || (signalType == SignalType.SELL && cached.getSuggestion() == SignalSuggestion.SELL);
                    if (!dirMatch) {
                        log.debug("[HardFilter] {} direction mismatch: filter={} composite={}",
                                label, cached.getSuggestion(), signalType);
                        descBuilder.append("| ").append(label)
                                .append("[DIR-MISMATCH:").append(cached.getSuggestion()).append("→VETOED] ");
                        signalType = SignalType.NEUTRAL;
                        strength = 0;
                        break;
                    }
                } else {
                    // 数值校验模式：校验与当前复合方向匹配的 applyTo 条件
                    String dirKey = signalType.name();
                    StringBuilder condDesc = new StringBuilder(label).append("[FILTER]");
                    boolean hasDirCond = false;
                    boolean allPass = true;

                    for (FilterCondition cond : conditions) {
                        if (!dirKey.equals(cond.getApplyTo())) continue;
                        hasDirCond = true;

                        Double actual = cached.getValues().get(cond.getField());
                        String expr = cond.getField() + opSymbol(cond.getOp()) + cond.getThreshold()
                                + "(@" + dirKey + ")";
                        if (actual == null || !evalCondition(actual, cond.getOp(), cond.getThreshold())) {
                            log.debug("[HardFilter] {} vetoed by {}: '{}' failed (actual={})",
                                    signalType, label, expr, actual);
                            condDesc.append("(FAIL:").append(expr).append(")");
                            allPass = false;
                            break;
                        }
                        condDesc.append("(OK:").append(expr).append(")");
                    }

                    if (hasDirCond) {
                        descBuilder.append("| ").append(condDesc).append(" ");
                        if (!allPass) {
                            signalType = SignalType.NEUTRAL;
                            strength = 0;
                            break;
                        }
                    }
                }
            }
        }

        return buildSignal(strategy, klines, allValues, signalType, strength, descBuilder);
    }

    // ── 私有工具方法 ──────────────────────────────────────────────────────────

    /**
     * 解析投票指标的投票方向。
     * <p>
     * 新方式：配置了 buyConditions/sellConditions 时，用 allValues 逐条求值。<br>
     * 旧方式（向后兼容）：未配置时回退到 result.getSuggestion()。
     * </p>
     */
    private SignalSuggestion resolveVote(StrategyIndicatorConfig config,
                                         IndicatorResult result,
                                         Map<String, Double> allValues) {
        if (!config.hasVotingConditions()) {
            // 旧方式：指标内部 suggestion（向后兼容，未来废弃）
            return result.getSuggestion() != null ? result.getSuggestion() : SignalSuggestion.NEUTRAL;
        }

        // 新方式：策略层条件判断
        boolean buyMatch = evalConditions(config.getBuyConditions(), allValues, result);
        if (buyMatch) return SignalSuggestion.BUY;

        boolean sellMatch = evalConditions(config.getSellConditions(), allValues, result);
        if (sellMatch) return SignalSuggestion.SELL;

        return SignalSuggestion.NEUTRAL;
    }

    /**
     * 判断条件列表是否全部满足（AND 逻辑）。
     * 先从 allValues（全局值表）查找字段，找不到时回退到当前指标的 values。
     */
    private boolean evalConditions(List<FilterCondition> conditions,
                                   Map<String, Double> allValues,
                                   IndicatorResult result) {
        if (conditions == null || conditions.isEmpty()) return false;
        for (FilterCondition cond : conditions) {
            Double actual = allValues.getOrDefault(cond.getField(),
                    result.getValues().get(cond.getField()));
            if (actual == null || !evalCondition(actual, cond.getOp(), cond.getThreshold())) {
                return false;
            }
        }
        return true;
    }

    private Signal buildSignal(Strategy strategy,
                               List<KLine> klines,
                               Map<String, Double> indicatorValues,
                               SignalType signalType,
                               int strength,
                               StringBuilder descBuilder) {
        BigDecimal price = BigDecimal.ZERO;
        long signalTime = System.currentTimeMillis();
        if (klines != null && !klines.isEmpty()) {
            KLine lastKline = klines.get(klines.size() - 1);
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

    private boolean evalCondition(double actual, String op, double threshold) {
        return switch (op) {
            case "GT"  -> actual > threshold;
            case "GTE" -> actual >= threshold;
            case "LT"  -> actual < threshold;
            case "LTE" -> actual <= threshold;
            case "EQ"  -> Math.abs(actual - threshold) < 1e-9;
            default    -> {
                log.warn("[SignalGen] Unknown operator: {}", op);
                yield false;
            }
        };
    }

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

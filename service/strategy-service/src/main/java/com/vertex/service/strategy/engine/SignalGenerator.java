package com.vertex.service.strategy.engine;

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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 信号生成器
 * <p>
 * 根据策略配置的指标，逐一计算后按<b>三桶加权投票</b>聚合，生成最终信号。
 * </p>
 *
 * <h3>评分规则</h3>
 * 每个指标根据 suggestion 累计权重到对应桶（BUY / SELL / NEUTRAL）。
 * NEUTRAL 桶始终作为守门员参与三桶竞争，避免噪音信号穿透。
 * 哪个桶权重最高，且严格大于另外两个桶，则输出该方向信号；否则输出 NEUTRAL。
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

        // ── 三桶评分变量 ────────────────────────────────────────────────────────
        int buyWeight = 0;
        int sellWeight = 0;
        int neutralWeight = 0;

        StringBuilder descBuilder = new StringBuilder();

        // ── 主循环：指标计算 + 三桶评分 ─────────────────────────────────────────
        for (StrategyIndicatorConfig config : configs) {
            TechnicalIndicator indicator = indicatorRegistry.get(config.getIndicatorType());
            int weight = config.getWeight() != null ? config.getWeight() : 50;

            // 获取该指标对应周期的K线
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

                // 描述中标注指标使用的周期（如果与策略默认不同）
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

        // ── 确定最终信号（NEUTRAL 桶作为守门员参与三桶竞争）──────────────────
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

        // ── price/signalTime 取策略默认周期的最新K线（主周期）────────────────────
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
        signal.setIndicators(com.alibaba.fastjson2.JSON.toJSONString(allIndicatorValues));
        signal.setDescription(descBuilder.toString().trim());

        return signal;
    }

}

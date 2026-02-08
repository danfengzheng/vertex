package com.vertex.service.strategy.engine;

import com.vertex.model.entity.quote.KLine;
import com.vertex.model.entity.strategy.Signal;
import com.vertex.model.entity.strategy.SignalType;
import com.vertex.model.entity.strategy.Strategy;
import com.vertex.model.dto.strategy.StrategyIndicatorConfig;
import com.vertex.service.strategy.indicator.IndicatorRegistry;
import com.vertex.service.strategy.indicator.IndicatorResult;
import com.vertex.service.strategy.indicator.IndicatorResult.SignalSuggestion;
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
 * 根据策略配置的指标，逐一计算后按权重加权聚合，生成最终信号。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SignalGenerator {

    private final IndicatorRegistry indicatorRegistry;

    /**
     * 评估策略，生成信号
     *
     * @param strategy 策略实体
     * @param configs  指标配置列表
     * @param klines   时间升序K线数据
     * @return Signal 实体（未设置 id）
     */
    public Signal evaluate(Strategy strategy, List<StrategyIndicatorConfig> configs, List<KLine> klines) {
        Map<String, Object> allIndicatorValues = new HashMap<>();
        int buyWeight = 0;
        int sellWeight = 0;
        int neutralWeight = 0;
        StringBuilder descBuilder = new StringBuilder();

        for (StrategyIndicatorConfig config : configs) {
            TechnicalIndicator indicator = indicatorRegistry.get(config.getIndicatorType());
            int weight = config.getWeight() != null ? config.getWeight() : 50;

            try {
                IndicatorResult result = indicator.calculate(klines, config.getParams());
                allIndicatorValues.putAll(result.getValues());

                switch (result.getSuggestion()) {
                    case BUY -> buyWeight += weight;
                    case SELL -> sellWeight += weight;
                    case NEUTRAL -> neutralWeight += weight;
                }

                descBuilder.append(config.getIndicatorType().getCode())
                        .append("=").append(result.getSuggestion())
                        .append("(w:").append(weight).append(") ");
            } catch (Exception e) {
                log.warn("Indicator {} calculation failed for strategy {}: {}",
                        config.getIndicatorType(), strategy.getName(), e.getMessage());
                neutralWeight += weight;
            }
        }

        // 确定最终信号
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

        KLine lastKline = klines.get(klines.size() - 1);

        Signal signal = new Signal();
        signal.setStrategyId(strategy.getId());
        signal.setStrategyName(strategy.getName());
        signal.setSymbol(strategy.getSymbol());
        signal.setExchange(strategy.getExchange());
        signal.setInterval(strategy.getInterval());
        signal.setSignalType(signalType);
        signal.setSignalStrength(strength);
        signal.setPrice(lastKline.getClose());
        signal.setSignalTime(lastKline.getOpenTime());
        signal.setIndicators(com.alibaba.fastjson2.JSON.toJSONString(allIndicatorValues));
        signal.setDescription(descBuilder.toString().trim());

        return signal;
    }
}

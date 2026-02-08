package com.vertex.service.strategy.indicator;

import com.vertex.model.entity.strategy.IndicatorType;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 指标注册表，自动发现并管理所有 TechnicalIndicator 实现
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IndicatorRegistry {

    private final List<TechnicalIndicator> indicators;
    private Map<IndicatorType, TechnicalIndicator> indicatorMap;

    @PostConstruct
    public void init() {
        indicatorMap = indicators.stream()
                .collect(Collectors.toMap(TechnicalIndicator::type, Function.identity()));
        log.info("Registered {} technical indicators: {}", indicatorMap.size(), indicatorMap.keySet());
    }

    /**
     * 获取指定类型的指标实现
     */
    public TechnicalIndicator get(IndicatorType type) {
        TechnicalIndicator indicator = indicatorMap.get(type);
        if (indicator == null) {
            throw new IllegalArgumentException("Unsupported indicator type: " + type);
        }
        return indicator;
    }

    /**
     * 是否支持该类型
     */
    public boolean supports(IndicatorType type) {
        return indicatorMap.containsKey(type);
    }
}

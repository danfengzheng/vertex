package com.vertex.service.strategy.indicator;

import com.vertex.model.entity.quote.KLine;
import com.vertex.model.entity.strategy.IndicatorType;

import java.util.List;
import java.util.Map;

/**
 * 技术指标接口
 * <p>
 * 实现此接口并标注 @Component 即可自动注册到 IndicatorRegistry。
 * </p>
 */
public interface TechnicalIndicator {

    /**
     * 指标类型
     */
    IndicatorType type();

    /**
     * 计算所需的最少数据点数
     */
    int requiredDataPoints(Map<String, Object> params);

    /**
     * 根据K线数据和参数计算指标
     *
     * @param klines 时间升序排列的K线数据
     * @param params 指标参数
     * @return 计算结果
     */
    IndicatorResult calculate(List<KLine> klines, Map<String, Object> params);
}

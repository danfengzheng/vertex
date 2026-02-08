package com.vertex.service.strategy.engine;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.vertex.common.core.exception.BizException;
import com.vertex.common.core.GlobalError;
import com.vertex.model.dto.strategy.StrategyIndicatorConfig;
import com.vertex.model.entity.quote.KLine;
import com.vertex.model.entity.quote.KLineInterval;
import com.vertex.model.entity.strategy.Signal;
import com.vertex.model.entity.strategy.Strategy;
import com.vertex.model.vo.strategy.SignalVO;
import com.vertex.service.quote.store.KLineStore;
import com.vertex.service.strategy.config.StrategyProperties;
import com.vertex.service.strategy.indicator.IndicatorRegistry;
import com.vertex.service.strategy.indicator.TechnicalIndicator;
import com.vertex.service.strategy.mapper.SignalMapper;
import com.vertex.service.strategy.mapper.StrategyMapper;
import com.vertex.service.strategy.store.SignalStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 策略引擎服务（编排器）
 * <p>
 * 负责：加载策略 → 获取K线 → 计算指标 → 生成信号 → 双写存储
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

    /**
     * 处理K线更新事件
     */
    public void processKLineUpdate(String exchange, String symbol, KLineInterval interval, List<KLine> klines) {
        // 如果配置了仅处理已收盘K线，则过滤
        if (properties.getEngine().isOnlyClosedKlines()) {
            klines = klines.stream().filter(k -> Boolean.TRUE.equals(k.getClosed())).toList();
            if (klines.isEmpty()) {
                return;
            }
        }

        // 查找匹配的已启用策略
        LambdaQueryWrapper<Strategy> wrapper = new LambdaQueryWrapper<Strategy>()
                .eq(Strategy::getExchange, exchange)
                .eq(Strategy::getSymbol, symbol)
                .eq(Strategy::getInterval, interval)
                .eq(Strategy::getEnabled, 1)
                .eq(Strategy::getDeleted, 0);

        List<Strategy> strategies = strategyMapper.selectList(wrapper);
        if (strategies.isEmpty()) {
            return;
        }

        for (Strategy strategy : strategies) {
            try {
                runStrategy(strategy);
            } catch (Exception e) {
                log.error("Strategy [{}] execution failed: {}", strategy.getName(), e.getMessage(), e);
            }
        }
    }

    /**
     * 手动触发策略执行
     */
    public void runStrategyNow(Long strategyId) {
        Strategy strategy = strategyMapper.selectById(strategyId);
        if (strategy == null) {
            throw new BizException(GlobalError.STRATEGY_NOT_FOUND);
        }
        runStrategy(strategy);
    }

    /**
     * 执行单个策略
     */
    private void runStrategy(Strategy strategy) {
        List<StrategyIndicatorConfig> configs = JSON.parseArray(
                strategy.getIndicatorConfigs(), StrategyIndicatorConfig.class);
        if (configs == null || configs.isEmpty()) {
            log.warn("Strategy [{}] has no indicator configs", strategy.getName());
            return;
        }

        // 计算所需的最大历史数据量
        int requiredDataPoints = configs.stream()
                .mapToInt(config -> {
                    TechnicalIndicator ind = indicatorRegistry.get(config.getIndicatorType());
                    return ind.requiredDataPoints(config.getParams());
                })
                .max()
                .orElse(50);

        int fetchSize = Math.min(requiredDataPoints + 10, properties.getEngine().getMaxKlineHistory());

        // 从 KLineStore 获取历史K线
        List<KLine> klines = klineStore.query(
                strategy.getExchange(),
                strategy.getSymbol(),
                strategy.getInterval(),
                null, null,
                fetchSize
        );

        if (klines.size() < requiredDataPoints) {
            log.debug("Strategy [{}] skipped: insufficient K-line data ({}/{})",
                    strategy.getName(), klines.size(), requiredDataPoints);
            return;
        }

        // 生成信号
        Signal signal = signalGenerator.evaluate(strategy, configs, klines);

        // 双写：MySQL + RocksDB
        signalMapper.insert(signal);
        try {
            signalStore.save(signal);
        } catch (Exception e) {
            log.warn("Failed to save signal to RocksDB, MySQL insert succeeded: {}", e.getMessage());
        }

        log.info("Strategy [{}] generated signal: {} (strength: {}) for {} {} {}",
                strategy.getName(), signal.getSignalType(), signal.getSignalStrength(),
                strategy.getExchange(), strategy.getSymbol(), strategy.getInterval().getCode());
    }
}

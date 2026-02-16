package com.vertex.service.strategy.config;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.vertex.model.dto.strategy.StrategyIndicatorConfig;
import com.vertex.model.entity.quote.KLineInterval;
import com.vertex.model.entity.strategy.Strategy;
import com.vertex.service.quote.source.QuoteDataSource;
import com.vertex.service.strategy.mapper.StrategyMapper;
import com.vertex.service.strategy.service.StrategyDataWarmupService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 系统启动时自动恢复已启用策略的行情订阅和数据预热。
 * <p>
 * 当系统重启后，之前启用的策略需要：
 * 1. 重新建立 WebSocket 连接并订阅对应交易对
 * 2. 检查 RocksDB 中的 K 线历史数据是否满足指标计算需求，不足则通过 REST API 自动补全
 * <p>
 * 本组件在 ApplicationReadyEvent 阶段（所有 Bean 初始化完成后）执行恢复逻辑。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StrategyStartupRecovery {

    private final StrategyMapper strategyMapper;
    private final List<QuoteDataSource> dataSources;
    private final StrategyDataWarmupService dataWarmupService;

    @EventListener(ApplicationReadyEvent.class)
    public void recoverSubscriptions() {
        log.info("[StartupRecovery] Checking for enabled strategies to recover subscriptions...");

        // 1. 查询所有已启用的策略
        LambdaQueryWrapper<Strategy> wrapper = new LambdaQueryWrapper<Strategy>()
                .eq(Strategy::getEnabled, 1)
                .eq(Strategy::getDeleted, 0);
        List<Strategy> enabledStrategies = strategyMapper.selectList(wrapper);

        if (enabledStrategies.isEmpty()) {
            log.info("[StartupRecovery] No enabled strategies found, skip.");
            return;
        }

        log.info("[StartupRecovery] Found {} enabled strategies, recovering...",
                enabledStrategies.size());

        // 2. 按交易所分组，同一交易所只建立一次连接
        Map<String, List<Strategy>> groupByExchange = enabledStrategies.stream()
                .collect(Collectors.groupingBy(s -> s.getExchange().toLowerCase()));

        for (Map.Entry<String, List<Strategy>> entry : groupByExchange.entrySet()) {
            String exchange = entry.getKey();
            List<Strategy> strategies = entry.getValue();

            try {
                recoverExchange(exchange, strategies);
            } catch (Exception e) {
                log.error("[StartupRecovery] Failed to recover subscriptions for exchange '{}': {}",
                        exchange, e.getMessage(), e);
            }
        }

        log.info("[StartupRecovery] Subscription recovery completed, starting data warmup...");

        // 3. 数据预热：检查每个策略的 K 线数据是否充足，不足则自动补全
        int totalBackfilled = 0;
        for (Strategy strategy : enabledStrategies) {
            try {
                int count = dataWarmupService.warmup(strategy);
                totalBackfilled += count;
            } catch (Exception e) {
                log.error("[StartupRecovery] Data warmup failed for strategy '{}': {}",
                        strategy.getName(), e.getMessage(), e);
            }
        }

        if (totalBackfilled > 0) {
            log.info("[StartupRecovery] Data warmup completed, {} K-lines backfilled in total.",
                    totalBackfilled);
        } else {
            log.info("[StartupRecovery] Data warmup completed, all strategies have sufficient data.");
        }

        log.info("[StartupRecovery] Full recovery completed.");
    }

    /**
     * 恢复单个交易所的连接和订阅（支持多周期指标）
     */
    private void recoverExchange(String exchange, List<Strategy> strategies) {
        QuoteDataSource ds = dataSources.stream()
                .filter(d -> exchange.equalsIgnoreCase(d.exchangeCode()))
                .findFirst()
                .orElse(null);

        if (ds == null) {
            log.warn("[StartupRecovery] No data source found for exchange: {}", exchange);
            return;
        }

        // 1. 如果未连接，自动启动
        if (!ds.isConnected()) {
            log.info("[StartupRecovery] Starting data source '{}'...", ds.exchangeCode());
            ds.start();
        }

        // 2. 获取当前已有的订阅，避免重复
        Set<String> existingSubscriptions = ds.getSubscriptions().stream()
                .map(sub -> sub.get("symbol") + ":" + sub.get("interval"))
                .collect(Collectors.toSet());

        // 3. 遍历策略的所有指标周期，构建完整的订阅列表
        Set<String> toSubscribe = new LinkedHashSet<>();
        for (Strategy strategy : strategies) {
            Set<KLineInterval> allIntervals = collectAllIntervals(strategy);
            for (KLineInterval iv : allIntervals) {
                String key = strategy.getSymbol() + ":" + iv.name();
                if (!existingSubscriptions.contains(key) && toSubscribe.add(key)) {
                    log.info("[StartupRecovery] Subscribing {}:{} on {}",
                            strategy.getSymbol(), iv.getCode(), ds.exchangeCode());
                    try {
                        ds.subscribe(strategy.getSymbol(), iv);
                    } catch (Exception e) {
                        log.error("[StartupRecovery] Failed to subscribe {}:{} on {}: {}",
                                strategy.getSymbol(), iv.getCode(),
                                ds.exchangeCode(), e.getMessage());
                    }
                }
            }
        }

        if (toSubscribe.isEmpty()) {
            log.info("[StartupRecovery] All subscriptions for '{}' already active, nothing to recover.",
                    ds.exchangeCode());
        } else {
            log.info("[StartupRecovery] Recovered {} subscriptions for '{}'.",
                    toSubscribe.size(), ds.exchangeCode());
        }
    }

    /** 收集策略所有用到的K线周期（含指标自定义周期，去重） */
    private Set<KLineInterval> collectAllIntervals(Strategy strategy) {
        Set<KLineInterval> intervals = new HashSet<>();
        intervals.add(strategy.getInterval());
        List<StrategyIndicatorConfig> configs = JSON.parseArray(
                strategy.getIndicatorConfigs(), StrategyIndicatorConfig.class);
        if (configs != null) {
            for (StrategyIndicatorConfig c : configs) {
                intervals.add(c.getInterval() != null ? c.getInterval() : strategy.getInterval());
            }
        }
        return intervals;
    }
}

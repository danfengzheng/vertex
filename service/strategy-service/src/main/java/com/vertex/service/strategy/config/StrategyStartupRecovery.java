package com.vertex.service.strategy.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
     * 恢复单个交易所的连接和订阅
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

        // 3. 去重后订阅：同一个 symbol:interval 只需订阅一次
        Set<String> toSubscribe = new LinkedHashSet<>();
        for (Strategy strategy : strategies) {
            String key = strategy.getSymbol() + ":" + strategy.getInterval().name();
            if (!existingSubscriptions.contains(key) && toSubscribe.add(key)) {
                log.info("[StartupRecovery] Subscribing {}:{} on {}",
                        strategy.getSymbol(), strategy.getInterval().getCode(), ds.exchangeCode());
                try {
                    ds.subscribe(strategy.getSymbol(), strategy.getInterval());
                } catch (Exception e) {
                    log.error("[StartupRecovery] Failed to subscribe {}:{} on {}: {}",
                            strategy.getSymbol(), strategy.getInterval().getCode(),
                            ds.exchangeCode(), e.getMessage());
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
}

package com.vertex.service.strategy.service;

import com.alibaba.fastjson2.JSON;
import com.vertex.model.dto.strategy.StrategyIndicatorConfig;
import com.vertex.model.entity.quote.KLine;
import com.vertex.model.entity.quote.KLineInterval;
import com.vertex.model.entity.strategy.Strategy;
import com.vertex.service.quote.source.rest.KLineRestClient;
import com.vertex.service.quote.store.KLineStore;
import com.vertex.service.strategy.indicator.IndicatorRegistry;
import com.vertex.service.strategy.indicator.TechnicalIndicator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 策略数据预热服务
 * <p>
 * 检查已启用策略所需的 K 线历史数据是否充足：
 * 1. 从 RocksDB 查询当前数据量
 * 2. 与指标所需的 requiredDataPoints 比较
 * 3. 不足时通过 REST API 拉取历史数据补全到 RocksDB
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StrategyDataWarmupService {

    private final KLineStore klineStore;
    private final IndicatorRegistry indicatorRegistry;
    private final List<KLineRestClient> restClients;

    /**
     * 检查并预热策略所需数据。
     *
     * @param strategy 已启用的策略
     * @return 补全的 K 线数量，0 表示数据已充足无需补全
     */
    public int warmup(Strategy strategy) {
        List<StrategyIndicatorConfig> configs = JSON.parseArray(
                strategy.getIndicatorConfigs(), StrategyIndicatorConfig.class);
        if (configs == null || configs.isEmpty()) {
            log.debug("[DataWarmup] Strategy '{}' has no indicator configs, skip.", strategy.getName());
            return 0;
        }

        // 1. 计算所有指标中最大的 requiredDataPoints
        int requiredDataPoints = configs.stream()
                .mapToInt(config -> {
                    try {
                        TechnicalIndicator ind = indicatorRegistry.get(config.getIndicatorType());
                        return ind.requiredDataPoints(config.getParams());
                    } catch (Exception e) {
                        log.warn("[DataWarmup] Unknown indicator type '{}' in strategy '{}'",
                                config.getIndicatorType(), strategy.getName());
                        return 50; // 默认值
                    }
                })
                .max()
                .orElse(50);

        // 额外留 10 条冗余
        int fetchSize = requiredDataPoints + 10;

        // 2. 查询 RocksDB 中现有数据量
        List<KLine> existingData = klineStore.query(
                strategy.getExchange(),
                strategy.getSymbol(),
                strategy.getInterval(),
                null, null,
                fetchSize,
                false  // 降序查最新
        );

        int existingCount = existingData.size();

        if (existingCount >= requiredDataPoints) {
            log.info("[DataWarmup] Strategy '{}' data sufficient: {}/{} K-lines available.",
                    strategy.getName(), existingCount, requiredDataPoints);
            return 0;
        }

        log.info("[DataWarmup] Strategy '{}' data insufficient: {}/{}, starting backfill via REST...",
                strategy.getName(), existingCount, requiredDataPoints);

        // 3. 通过 REST API 拉取历史数据
        return backfillViaRest(strategy, fetchSize);
    }

    /**
     * 通过 REST 接口拉取最近 N 条 K 线并存入 RocksDB
     */
    private int backfillViaRest(Strategy strategy, int limit) {
        try {
            KLineRestClient client = restClients.stream()
                    .filter(c -> strategy.getExchange().equalsIgnoreCase(c.exchangeCode()))
                    .findFirst()
                    .orElse(null);

            if (client == null) {
                log.warn("[DataWarmup] No REST client found for exchange '{}', skip backfill.",
                        strategy.getExchange());
                return 0;
            }

            // REST API 单次限制（OKX=300, Binance=1000），分批拉取
            int batchLimit = "okx".equalsIgnoreCase(strategy.getExchange()) ? 300 : 1000;
            int remaining = limit;
            int totalFetched = 0;
            Long endTime = null; // 从最新开始向前查

            while (remaining > 0) {
                int batch = Math.min(remaining, batchLimit);
                List<KLine> klines = client.fetchKLines(
                        strategy.getSymbol(),
                        strategy.getInterval(),
                        null,    // startTime: null 表示由 endTime 向前
                        endTime,
                        batch
                );

                if (klines.isEmpty()) {
                    break;
                }

                // 存入 RocksDB（saveBatch 内部会处理去重/覆盖）
                klineStore.saveBatch(klines);
                totalFetched += klines.size();
                remaining -= klines.size();

                // 更新游标：取最早一条的 openTime - 1 作为下一批的 endTime
                long earliestTime = klines.stream()
                        .mapToLong(KLine::getOpenTime)
                        .min()
                        .orElse(0);
                endTime = earliestTime - 1;

                // 如果返回数量少于请求数量，说明已无更多数据
                if (klines.size() < batch) {
                    break;
                }
            }

            log.info("[DataWarmup] Strategy '{}' backfill completed: {} K-lines fetched for {}:{} on {}.",
                    strategy.getName(), totalFetched,
                    strategy.getSymbol(), strategy.getInterval().getCode(),
                    strategy.getExchange());

            return totalFetched;
        } catch (Exception e) {
            log.error("[DataWarmup] Backfill failed for strategy '{}': {}",
                    strategy.getName(), e.getMessage(), e);
            return 0;
        }
    }
}

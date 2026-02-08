package com.vertex.service.quote.controller;

import com.vertex.api.quote.IKLineService;
import com.vertex.common.core.GlobalError;
import com.vertex.common.core.exception.BizException;
import com.vertex.model.dto.quote.KLineQueryDTO;
import com.vertex.model.dto.quote.SubscribeRequestDTO;
import com.vertex.model.entity.quote.KLine;
import com.vertex.model.entity.quote.KLineInterval;
import com.vertex.model.vo.quote.DataSourceStatusVO;
import com.vertex.service.quote.source.QuoteDataSource;
import com.vertex.service.quote.source.rest.KLineRestClient;
import com.vertex.service.quote.store.KLineStore;
import com.vertex.web.response.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 行情数据源管理控制器
 * <p>
 * 提供 WebSocket 数据源的生命周期管理、K线订阅/取消订阅，
 * 以及通过 REST API 进行历史数据补全。
 */
@Slf4j
@Tag(name = "行情数据源管理")
@Validated
@RestController
@RequestMapping("/admin/quote/source")
@RequiredArgsConstructor
public class QuoteSourceController {

    private final List<QuoteDataSource> dataSources;
    private final List<KLineRestClient> restClients;
    private final IKLineService klineService;
    private final KLineStore klineStore;

    @Operation(summary = "查看所有数据源状态")
    @GetMapping("/status")
    public Result<List<DataSourceStatusVO>> status() {
        List<DataSourceStatusVO> statusList = dataSources.stream()
                .map(ds -> DataSourceStatusVO.builder()
                        .exchange(ds.exchangeCode())
                        .connected(ds.isConnected())
                        .build())
                .toList();
        return Result.success(statusList);
    }

    @Operation(summary = "启动数据源")
    @PostMapping("/start")
    public Result<Void> start(@RequestParam @NotBlank(message = "交易所不能为空") String exchange) {
        QuoteDataSource ds = findDataSource(exchange);
        ds.start();
        return Result.success();
    }

    @Operation(summary = "停止数据源")
    @PostMapping("/stop")
    public Result<Void> stop(@RequestParam @NotBlank(message = "交易所不能为空") String exchange) {
        QuoteDataSource ds = findDataSource(exchange);
        ds.stop();
        return Result.success();
    }

    @Operation(summary = "订阅K线")
    @PostMapping("/subscribe")
    public Result<Void> subscribe(@RequestBody @Validated SubscribeRequestDTO dto) {
        QuoteDataSource ds = findDataSource(dto.getExchange());
        if (!ds.isConnected()) {
            throw new BizException(GlobalError.EXCHANGE_CONNECT_ERROR);
        }
        ds.subscribe(dto.getSymbol(), dto.getInterval());
        return Result.success();
    }

    @Operation(summary = "取消订阅K线")
    @PostMapping("/unsubscribe")
    public Result<Void> unsubscribe(@RequestBody @Validated SubscribeRequestDTO dto) {
        QuoteDataSource ds = findDataSource(dto.getExchange());
        ds.unsubscribe(dto.getSymbol(), dto.getInterval());
        return Result.success();
    }

    @Operation(summary = "历史K线智能补全（REST），支持时间段选择、全部周期、增量补全")
    @PostMapping("/backfill")
    public Result<Integer> backfill(@RequestBody @Validated KLineQueryDTO query) {
        KLineRestClient client = findRestClient(query.getExchange());

        // interval 为 null 表示全部周期
        List<KLineInterval> intervals = query.getInterval() != null
                ? List.of(query.getInterval())
                : Arrays.asList(KLineInterval.values());

        int totalCount = 0;
        for (KLineInterval interval : intervals) {
            int count = backfillSingleInterval(client, query, interval);
            totalCount += count;
            log.info("[Backfill] {}:{} {} -> {} records",
                    query.getExchange(), query.getSymbol(), interval.getCode(), count);
        }
        return Result.success(totalCount);
    }

    /**
     * 单个周期的智能补全
     * <p>
     * 策略：查询数据库已有数据，如果连续数据 >= 100 条则只补缺口（头部+尾部），
     * 否则全量覆盖拉取。
     */
    private int backfillSingleInterval(KLineRestClient client, KLineQueryDTO query, KLineInterval interval) {
        int batchLimit = "okx".equalsIgnoreCase(query.getExchange()) ? 300 : 1000;

        // 无时间段时走单次查询
        if (query.getStartTime() == null || query.getEndTime() == null) {
            int limit = query.getLimit() != null ? query.getLimit() : 500;
            List<KLine> klines = client.fetchKLines(
                    query.getSymbol(), interval,
                    query.getStartTime(), query.getEndTime(),
                    Math.min(limit, batchLimit));
            if (!klines.isEmpty()) {
                klineService.saveBatch(klines);
            }
            return klines.size();
        }

        long startTime = query.getStartTime();
        long endTime = query.getEndTime();

        // 查询数据库已有数据
        List<KLine> existingData = klineStore.query(
                query.getExchange(), query.getSymbol(), interval,
                startTime, endTime, Integer.MAX_VALUE);

        // 检查连续性：相邻两条的 openTime 之差应等于 interval 的毫秒数
        int continuousCount = countContinuous(existingData, interval);

        if (continuousCount >= 100 && !existingData.isEmpty()) {
            // 增量补全：只补头部缺口和尾部缺口
            log.info("[Backfill] Smart mode: found {} continuous records, filling gaps only", continuousCount);
            int count = 0;

            // 补头部：startTime ~ 已有数据最早 openTime
            long existingStart = existingData.get(0).getOpenTime();
            if (existingStart > startTime) {
                count += fetchAndSave(client, query.getSymbol(), query.getExchange(), interval,
                        startTime, existingStart - 1, batchLimit);
            }

            // 补尾部：已有数据最晚 closeTime ~ endTime
            long existingEnd = existingData.get(existingData.size() - 1).getCloseTime();
            if (existingEnd < endTime) {
                count += fetchAndSave(client, query.getSymbol(), query.getExchange(), interval,
                        existingEnd + 1, endTime, batchLimit);
            }

            return count;
        }

        // 全量拉取
        log.info("[Backfill] Full mode: continuous={}, fetching entire range", continuousCount);
        return fetchAndSave(client, query.getSymbol(), query.getExchange(), interval,
                startTime, endTime, batchLimit);
    }

    /**
     * 分批拉取并保存
     */
    private int fetchAndSave(KLineRestClient client, String symbol, String exchange,
                             KLineInterval interval, long startTime, long endTime, int batchLimit) {
        long intervalMillis = interval.getMillis();
        long windowMillis = intervalMillis * batchLimit;
        long cursor = startTime;
        int totalCount = 0;

        while (cursor < endTime) {
            long windowEnd = Math.min(cursor + windowMillis, endTime);
            List<KLine> batch = client.fetchKLines(symbol, interval, cursor, windowEnd, batchLimit);

            if (!batch.isEmpty()) {
                klineService.saveBatch(batch);
                totalCount += batch.size();
                long lastCloseTime = batch.get(batch.size() - 1).getCloseTime();
                cursor = lastCloseTime + 1;
            } else {
                cursor = windowEnd + 1;
            }
        }
        return totalCount;
    }

    /**
     * 计算最长连续K线数量
     */
    private int countContinuous(List<KLine> data, KLineInterval interval) {
        if (data == null || data.size() < 2) {
            return data == null ? 0 : data.size();
        }
        long millis = interval.getMillis();
        int maxContinuous = 1;
        int current = 1;
        for (int i = 1; i < data.size(); i++) {
            long gap = data.get(i).getOpenTime() - data.get(i - 1).getOpenTime();
            if (gap == millis) {
                current++;
                maxContinuous = Math.max(maxContinuous, current);
            } else {
                current = 1;
            }
        }
        return maxContinuous;
    }

    // ==================== 辅助方法 ====================

    private QuoteDataSource findDataSource(String exchange) {
        return dataSources.stream()
                .filter(ds -> exchange.equalsIgnoreCase(ds.exchangeCode()))
                .findFirst()
                .orElseThrow(() -> new BizException(GlobalError.EXCHANGE_CONNECT_ERROR));
    }

    private KLineRestClient findRestClient(String exchange) {
        return restClients.stream()
                .filter(c -> exchange.equalsIgnoreCase(c.exchangeCode()))
                .findFirst()
                .orElseThrow(() -> new BizException(GlobalError.EXCHANGE_CONNECT_ERROR));
    }
}

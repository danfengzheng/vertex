package com.vertex.service.quote.controller;

import com.vertex.api.quote.IKLineService;
import com.vertex.common.core.GlobalError;
import com.vertex.common.core.exception.BizException;
import com.vertex.model.dto.quote.KLineQueryDTO;
import com.vertex.model.dto.quote.SubscribeRequestDTO;
import com.vertex.model.entity.quote.KLine;
import com.vertex.model.entity.quote.KLineInterval;
import com.vertex.model.vo.quote.DataSourceStatusVO;
import com.vertex.service.quote.service.QuoteBackfillService;
import com.vertex.service.quote.source.QuoteDataSource;
import com.vertex.service.quote.source.rest.KLineRestClient;
import com.vertex.web.response.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import com.vertex.common.core.annotation.RequiresPermission;

/**
 * 行情数据源管理控制器
 * <p>
 * 提供 WebSocket 数据源的生命周期管理、K线订阅/取消订阅，
 * 以及通过 REST API 进行历史数据补全（异步执行）。
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
    private final QuoteBackfillService quoteBackfillService;

    /** 异步补全任务线程池 */
    private final ExecutorService backfillExecutor = Executors.newFixedThreadPool(2);

    /** 补全任务进度追踪 */
    private final Map<String, BackfillProgress> progressMap = new ConcurrentHashMap<>();

    @RequiresPermission("quote:source")
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

    @RequiresPermission("quote:source")
    @Operation(summary = "启动数据源")
    @PostMapping("/start")
    public Result<Void> start(@RequestParam @NotBlank(message = "交易所不能为空") String exchange) {
        QuoteDataSource ds = findDataSource(exchange);
        ds.start();
        return Result.success();
    }

    @RequiresPermission("quote:source")
    @Operation(summary = "停止数据源")
    @PostMapping("/stop")
    public Result<Void> stop(@RequestParam @NotBlank(message = "交易所不能为空") String exchange) {
        QuoteDataSource ds = findDataSource(exchange);
        ds.stop();
        return Result.success();
    }

    @RequiresPermission("quote:source")
    @Operation(summary = "查看指定交易所的活跃订阅列表")
    @GetMapping("/subscriptions")
    public Result<List<Map<String, String>>> subscriptions(
            @RequestParam @NotBlank(message = "交易所不能为空") String exchange) {
        QuoteDataSource ds = findDataSource(exchange);
        return Result.success(ds.getSubscriptions());
    }

    @RequiresPermission("quote:source")
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

    @RequiresPermission("quote:source")
    @Operation(summary = "取消订阅K线")
    @PostMapping("/unsubscribe")
    public Result<Void> unsubscribe(@RequestBody @Validated SubscribeRequestDTO dto) {
        QuoteDataSource ds = findDataSource(dto.getExchange());
        ds.unsubscribe(dto.getSymbol(), dto.getInterval());
        return Result.success();
    }

    @RequiresPermission("quote:source")
    @Operation(summary = "提交历史K线补全任务（异步）")
    @PostMapping("/backfill")
    public Result<String> backfill(@RequestBody @Validated KLineQueryDTO query) {
        // 校验交易所 REST 客户端存在
        findRestClient(query.getExchange());

        String taskId = UUID.randomUUID().toString().replace("-", "").substring(0, 12);

        List<KLineInterval> intervals = query.getInterval() != null
                ? List.of(query.getInterval())
                : Arrays.asList(KLineInterval.values());

        BackfillProgress progress = new BackfillProgress(intervals.size());
        progressMap.put(taskId, progress);

        backfillExecutor.submit(() -> {
            try {
                KLineRestClient client = findRestClient(query.getExchange());
                for (KLineInterval interval : intervals) {
                    progress.currentInterval = interval.getCode();
                    int count = backfillSingleInterval(client, query, interval);
                    progress.completedRecords += count;
                    progress.completedIntervals++;
                    log.info("[Backfill] task={} {}:{} {} -> {} records",
                            taskId, query.getExchange(), query.getSymbol(), interval.getCode(), count);
                }
                progress.status = "done";
            } catch (Exception e) {
                log.error("[Backfill] task={} failed", taskId, e);
                progress.status = "error";
                progress.errorMessage = e.getMessage();
            }
        });

        return Result.success(taskId);
    }

    @RequiresPermission("quote:source")
    @Operation(summary = "查询补全任务进度")
    @GetMapping("/backfill/progress")
    public Result<Map<String, Object>> backfillProgress(@RequestParam String taskId) {
        BackfillProgress progress = progressMap.get(taskId);
        if (progress == null) {
            return Result.success(Map.of("status", "not_found"));
        }

        Map<String, Object> result = new ConcurrentHashMap<>();
        result.put("status", progress.status);
        result.put("totalIntervals", progress.totalIntervals);
        result.put("completedIntervals", progress.completedIntervals);
        result.put("completedRecords", progress.completedRecords);
        result.put("currentInterval", progress.currentInterval != null ? progress.currentInterval : "");
        if (progress.errorMessage != null) {
            result.put("errorMessage", progress.errorMessage);
        }

        // 任务完成后清理
        if ("done".equals(progress.status) || "error".equals(progress.status)) {
            progressMap.remove(taskId);
        }

        return Result.success(result);
    }

    // ==================== 补全逻辑 ====================

    /**
     * 单个周期补全：无时间范围时单次拉取最近 N 条；有范围时按缺口（首/中/尾）逐段回填。
     */
    private int backfillSingleInterval(KLineRestClient client, KLineQueryDTO query, KLineInterval interval) {
        int batchLimit = "okx".equalsIgnoreCase(query.getExchange()) ? 300 : 1000;

        if (query.getStartTime() == null || query.getEndTime() == null) {
            int limit = query.getLimit() != null ? query.getLimit() : 500;
            List<KLine> klines = client.fetchKLines(
                    query.getSymbol(), interval,
                    query.getStartTime(), query.getEndTime(),
                    Math.min(limit, batchLimit));
            if (klines.isEmpty()) {
                return 0;
            }
            return klineService.saveBatchFillingGapsOnly(klines);
        }

        return quoteBackfillService.fillGapsInRange(
                query.getExchange(), query.getSymbol(), interval,
                query.getStartTime(), query.getEndTime());
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

    /**
     * 补全任务进度
     */
    private static class BackfillProgress {
        volatile String status = "running";
        volatile int totalIntervals;
        volatile int completedIntervals = 0;
        volatile int completedRecords = 0;
        volatile String currentInterval;
        volatile String errorMessage;

        BackfillProgress(int totalIntervals) {
            this.totalIntervals = totalIntervals;
        }
    }
}

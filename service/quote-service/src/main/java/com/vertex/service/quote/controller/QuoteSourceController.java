package com.vertex.service.quote.controller;

import com.vertex.api.quote.IKLineService;
import com.vertex.common.core.GlobalError;
import com.vertex.common.core.exception.BizException;
import com.vertex.model.dto.quote.KLineQueryDTO;
import com.vertex.model.dto.quote.SubscribeRequestDTO;
import com.vertex.model.entity.quote.KLine;
import com.vertex.model.vo.quote.DataSourceStatusVO;
import com.vertex.service.quote.source.QuoteDataSource;
import com.vertex.service.quote.source.rest.KLineRestClient;
import com.vertex.web.response.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 行情数据源管理控制器
 * <p>
 * 提供 WebSocket 数据源的生命周期管理、K线订阅/取消订阅，
 * 以及通过 REST API 进行历史数据补全。
 */
@Tag(name = "行情数据源管理")
@Validated
@RestController
@RequestMapping("/admin/quote/source")
@RequiredArgsConstructor
public class QuoteSourceController {

    private final List<QuoteDataSource> dataSources;
    private final List<KLineRestClient> restClients;
    private final IKLineService klineService;

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

    @Operation(summary = "历史K线补全（REST）")
    @PostMapping("/backfill")
    public Result<Integer> backfill(@RequestBody @Validated KLineQueryDTO query) {
        KLineRestClient client = findRestClient(query.getExchange());
        int limit = query.getLimit() != null ? query.getLimit() : 500;

        List<KLine> klines = client.fetchKLines(
                query.getSymbol(),
                query.getInterval(),
                query.getStartTime(),
                query.getEndTime(),
                limit
        );

        if (!klines.isEmpty()) {
            klineService.saveBatch(klines);
        }
        return Result.success(klines.size());
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

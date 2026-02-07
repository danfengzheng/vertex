package com.vertex.service.quote.controller;

import com.vertex.api.quote.IKLineService;
import com.vertex.model.dto.quote.KLineQueryDTO;
import com.vertex.model.entity.quote.KLineInterval;
import com.vertex.model.vo.quote.KLineVO;
import com.vertex.web.response.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * K线数据查询控制器
 */
@Tag(name = "K线数据")
@Validated
@RestController
@RequestMapping("/admin/quote/kline")
@RequiredArgsConstructor
public class KLineController {

    private final IKLineService klineService;

    @Operation(summary = "查询K线数据")
    @GetMapping
    public Result<List<KLineVO>> query(@Validated KLineQueryDTO query) {
        return Result.success(klineService.query(query));
    }

    @Operation(summary = "获取最新K线")
    @GetMapping("/latest")
    public Result<KLineVO> latest(@RequestParam @NotBlank(message = "交易对不能为空") String symbol,
                                  @RequestParam @NotBlank(message = "交易所不能为空") String exchange,
                                  @RequestParam @NotNull(message = "K线周期不能为空") KLineInterval interval) {
        return Result.success(klineService.getLatest(symbol, exchange, interval));
    }
}

package com.vertex.service.strategy.controller;

import com.vertex.web.response.Result;
import com.vertex.model.dto.strategy.BacktestConfigDTO;
import com.vertex.model.vo.strategy.BacktestResultVO;
import com.vertex.service.strategy.backtest.BacktestService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 策略回测控制器
 */
@Tag(name = "策略回测")
@Validated
@RestController
@RequestMapping("/admin/backtest")
@RequiredArgsConstructor
public class BacktestController {

    private final BacktestService backtestService;

    @PostMapping("/run")
    @Operation(summary = "执行策略回测")
    public Result<BacktestResultVO> runBacktest(@RequestBody @Validated BacktestConfigDTO config) {
        return Result.success(backtestService.runBacktest(config));
    }
}

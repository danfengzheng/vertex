package com.vertex.service.strategy.controller;

import com.vertex.web.response.Result;
import com.vertex.model.dto.strategy.BacktestConfigDTO;
import com.vertex.model.vo.strategy.BacktestResultVO;
import com.vertex.model.entity.strategy.Strategy;
import com.vertex.service.strategy.backtest.BacktestService;
import com.vertex.service.strategy.mapper.StrategyMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import com.vertex.common.core.annotation.RequiresPermission;

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
    private final StrategyMapper strategyMapper;

    @RequiresPermission("strategy:config")
    @PostMapping("/run")
    @Operation(summary = "执行策略回测")
    public Result<BacktestResultVO> runBacktest(@RequestBody @Validated BacktestConfigDTO config) {
        return Result.success(backtestService.runBacktest(config));
    }

    @RequiresPermission("strategy:config")
    @PostMapping("/quick")
    @Operation(summary = "快速回测 - 使用最近N天的数据对策略进行完整回测")
    public Result<BacktestResultVO> quickBacktest(
            @RequestParam Long strategyId,
            @RequestParam(defaultValue = "30") Integer days,
            @RequestParam(required = false) BigDecimal initialCapital,
            @RequestParam(required = false) BigDecimal positionRatio,
            @RequestParam(required = false) BigDecimal feeRate) {
        long endTime = System.currentTimeMillis();
        long startTime = endTime - (long) days * 24 * 60 * 60 * 1000;

        // 从策略配置读取默认值，参数传入的值优先
        Strategy strategy = strategyMapper.selectById(strategyId);
        BigDecimal defaultFeeRate = new BigDecimal("0.001");
        BigDecimal defaultRatio = BigDecimal.ONE;
        BigDecimal defaultCapital = new BigDecimal("10000");
        if (strategy != null) {
            if (strategy.getFeeRate() != null) defaultFeeRate = strategy.getFeeRate();
            if (strategy.getPositionRatio() != null) defaultRatio = strategy.getPositionRatio();
            if (strategy.getInitialCapital() != null) defaultCapital = strategy.getInitialCapital();
        }

        BacktestConfigDTO config = new BacktestConfigDTO();
        config.setStrategyId(strategyId);
        config.setStartTime(startTime);
        config.setEndTime(endTime);
        config.setInitialCapital(initialCapital != null ? initialCapital : defaultCapital);
        config.setPositionRatio(positionRatio != null ? positionRatio : defaultRatio);
        config.setFeeRate(feeRate != null ? feeRate : defaultFeeRate);

        return Result.success(backtestService.runBacktest(config));
    }
}

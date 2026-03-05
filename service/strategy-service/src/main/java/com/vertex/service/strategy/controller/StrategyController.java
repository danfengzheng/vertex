package com.vertex.service.strategy.controller;

import com.vertex.api.strategy.IStrategyService;
import com.vertex.common.core.page.PageResult;
import com.vertex.model.dto.strategy.StrategyCreateDTO;
import com.vertex.model.dto.strategy.StrategyQueryDTO;
import com.vertex.model.dto.strategy.StrategyUpdateDTO;
import com.vertex.model.vo.strategy.StrategyVO;
import com.vertex.web.response.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import com.vertex.common.core.annotation.RequiresPermission;

/**
 * 策略管理 Controller
 */
@Tag(name = "策略管理")
@Validated
@RestController
@RequestMapping("/admin/strategy")
@RequiredArgsConstructor
public class StrategyController {

    private final IStrategyService strategyService;

    @RequiresPermission("strategy:config")
    @PostMapping
    @Operation(summary = "创建策略")
    public Result<Long> create(@RequestBody @Validated StrategyCreateDTO dto) {
        return Result.success(strategyService.create(dto));
    }

    @RequiresPermission("strategy:config")
    @PutMapping
    @Operation(summary = "更新策略")
    public Result<Void> update(@RequestBody @Validated StrategyUpdateDTO dto) {
        strategyService.update(dto);
        return Result.success();
    }

    @RequiresPermission("strategy:config")
    @DeleteMapping("/{id}")
    @Operation(summary = "删除策略")
    public Result<Void> delete(@PathVariable Long id) {
        strategyService.delete(id);
        return Result.success();
    }

    @RequiresPermission("strategy:config")
    @GetMapping("/{id}")
    @Operation(summary = "策略详情")
    public Result<StrategyVO> getById(@PathVariable Long id) {
        return Result.success(strategyService.getById(id));
    }

    @RequiresPermission("strategy:config")
    @GetMapping("/page")
    @Operation(summary = "策略分页查询")
    public Result<PageResult<StrategyVO>> page(@Validated StrategyQueryDTO query) {
        return Result.success(strategyService.page(query));
    }

    @RequiresPermission("strategy:config")
    @PostMapping("/{id}/enable")
    @Operation(summary = "启用策略")
    public Result<Void> enable(@PathVariable Long id) {
        strategyService.enable(id);
        return Result.success();
    }

    @RequiresPermission("strategy:config")
    @PostMapping("/{id}/disable")
    @Operation(summary = "禁用策略")
    public Result<Void> disable(@PathVariable Long id) {
        strategyService.disable(id);
        return Result.success();
    }
}

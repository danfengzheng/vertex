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

    @RequiresPermission("strategy:config")
    @PostMapping("/{id}/copy")
    @Operation(summary = "复制策略", description = "克隆现有策略的所有配置，生成新策略（默认禁用，名称加(副本)后缀）")
    public Result<Long> copy(@PathVariable Long id) {
        return Result.success(strategyService.copy(id));
    }

    @RequiresPermission("strategy:config")
    @PostMapping("/batch/add-divergence-exit")
    @Operation(summary = "批量追加背离出场配置",
               description = "为所有当前运行中（enabled=1）的策略自动追加背离（DIVERGENCE）出场指标配置。" +
                             "若策略已包含 DIVERGENCE 出场配置则跳过，不重复添加。返回实际更新的策略数量。")
    public Result<Integer> addDivergenceExitToRunningStrategies() {
        return Result.success(strategyService.addDivergenceExitToRunningStrategies());
    }
}

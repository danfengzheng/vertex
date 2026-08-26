package com.vertex.service.strategy.controller;

import com.vertex.api.strategy.ISignalService;
import com.vertex.common.core.page.PageResult;
import com.vertex.model.dto.strategy.SignalCursorDTO;
import com.vertex.model.dto.strategy.SignalQueryDTO;
import com.vertex.model.vo.strategy.SignalCursorResult;
import com.vertex.model.vo.strategy.SignalVO;
import com.vertex.web.response.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import com.vertex.common.core.annotation.RequiresPermission;

/**
 * 信号管理 Controller
 */
@Tag(name = "信号管理")
@Validated
@RestController
@RequestMapping("/admin/signal")
@RequiredArgsConstructor
public class SignalController {

    private final ISignalService signalService;

    @RequiresPermission("strategy:signal")
    @GetMapping("/page")
    @Operation(summary = "信号分页查询（offset 分页，已跳过 COUNT；深页请用 /cursor）")
    public Result<PageResult<SignalVO>> page(@Validated SignalQueryDTO query) {
        return Result.success(signalService.page(query));
    }

    @RequiresPermission("strategy:signal")
    @GetMapping("/cursor")
    @Operation(summary = "信号游标分页（性能恒定，推荐用于加载更多 UI）")
    public Result<SignalCursorResult<SignalVO>> pageByCursor(@Validated SignalCursorDTO query) {
        return Result.success(signalService.pageByCursor(query));
    }

    @RequiresPermission("strategy:signal")
    @GetMapping("/{id}")
    @Operation(summary = "信号详情")
    public Result<SignalVO> getById(@PathVariable Long id) {
        return Result.success(signalService.getById(id));
    }

    @RequiresPermission("strategy:signal")
    @PostMapping("/analyze")
    @Operation(summary = "手动触发策略分析")
    public Result<Void> analyze(@RequestParam Long strategyId) {
        signalService.triggerAnalysis(strategyId);
        return Result.success();
    }
}

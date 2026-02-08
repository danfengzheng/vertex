package com.vertex.service.strategy.controller;

import com.vertex.api.strategy.ISignalService;
import com.vertex.common.core.page.PageResult;
import com.vertex.model.dto.strategy.SignalQueryDTO;
import com.vertex.model.vo.strategy.SignalVO;
import com.vertex.web.response.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

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

    @GetMapping("/page")
    @Operation(summary = "信号分页查询")
    public Result<PageResult<SignalVO>> page(@Validated SignalQueryDTO query) {
        return Result.success(signalService.page(query));
    }

    @GetMapping("/{id}")
    @Operation(summary = "信号详情")
    public Result<SignalVO> getById(@PathVariable Long id) {
        return Result.success(signalService.getById(id));
    }

    @PostMapping("/analyze")
    @Operation(summary = "手动触发策略分析")
    public Result<Void> analyze(@RequestParam Long strategyId) {
        signalService.triggerAnalysis(strategyId);
        return Result.success();
    }
}

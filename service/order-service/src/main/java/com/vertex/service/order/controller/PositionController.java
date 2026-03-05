package com.vertex.service.order.controller;

import com.vertex.api.trading.IPositionService;
import com.vertex.common.core.page.PageResult;
import com.vertex.model.dto.trading.PositionQueryDTO;
import com.vertex.model.vo.trading.PositionVO;
import com.vertex.web.response.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import com.vertex.common.core.annotation.RequiresPermission;

/**
 * 持仓管理
 */
@Tag(name = "持仓管理")
@Validated
@RestController
@RequestMapping("/admin/trade/position")
@RequiredArgsConstructor
public class PositionController {

    private final IPositionService positionService;

    @RequiresPermission("trade:position")
    @Operation(summary = "持仓分页查询")
    @GetMapping("/page")
    public Result<PageResult<PositionVO>> page(@Validated PositionQueryDTO query) {
        return Result.success(positionService.page(query));
    }

    @RequiresPermission("trade:position")
    @Operation(summary = "持仓详情")
    @GetMapping("/{id}")
    public Result<PositionVO> getById(@PathVariable Long id) {
        return Result.success(positionService.getById(id));
    }

    @RequiresPermission("trade:position")
    @Operation(summary = "手动平仓")
    @PostMapping("/{id}/close")
    public Result<Void> close(@PathVariable Long id) {
        positionService.close(id);
        return Result.success();
    }
}

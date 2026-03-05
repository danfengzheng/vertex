package com.vertex.admin.web.controller;

import com.vertex.api.trading.IOrderService;
import com.vertex.common.core.page.PageResult;
import com.vertex.model.dto.trading.OrderCreateDTO;
import com.vertex.model.dto.trading.OrderQueryDTO;
import com.vertex.model.vo.trading.OrderVO;
import com.vertex.service.order.service.impl.TradeExecutionService;
import com.vertex.web.response.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import com.vertex.common.core.annotation.RequiresPermission;

/**
 * 交易订单管理
 */
@Tag(name = "交易订单")
@Validated
@RestController
@RequestMapping("/admin/trade/order")
@RequiredArgsConstructor
public class OrderController {

    private final IOrderService orderService;
    private final TradeExecutionService tradeExecutionService;

    @RequiresPermission("trade:order")
    @Operation(summary = "手动下单")
    @PostMapping
    public Result<Long> create(@RequestBody @Validated OrderCreateDTO dto) {
        return Result.success(orderService.create(dto));
    }

    @RequiresPermission("trade:order")
    @Operation(summary = "确认待执行订单")
    @PostMapping("/{id}/confirm")
    public Result<Void> confirm(@PathVariable Long id) {
        tradeExecutionService.confirmOrder(id, null);
        return Result.success();
    }

    @RequiresPermission("trade:order")
    @Operation(summary = "拒绝待执行订单")
    @PostMapping("/{id}/reject")
    public Result<Void> reject(@PathVariable Long id) {
        tradeExecutionService.rejectOrder(id);
        return Result.success();
    }

    @RequiresPermission("trade:order")
    @Operation(summary = "取消订单")
    @PostMapping("/{id}/cancel")
    public Result<Void> cancel(@PathVariable Long id) {
        orderService.cancel(id);
        return Result.success();
    }

    @RequiresPermission("trade:order")
    @Operation(summary = "订单分页查询")
    @GetMapping("/page")
    public Result<PageResult<OrderVO>> page(@Validated OrderQueryDTO query) {
        return Result.success(orderService.page(query));
    }

    @RequiresPermission("trade:order")
    @Operation(summary = "订单详情")
    @GetMapping("/{id}")
    public Result<OrderVO> getById(@PathVariable Long id) {
        return Result.success(orderService.getById(id));
    }
}

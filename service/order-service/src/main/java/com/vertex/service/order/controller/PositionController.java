package com.vertex.service.order.controller;

import com.vertex.api.trading.IPositionService;
import com.vertex.common.core.GlobalError;
import com.vertex.common.core.exception.BizException;
import com.vertex.common.core.page.PageResult;
import com.vertex.model.dto.trading.PositionQueryDTO;
import com.vertex.model.entity.strategy.Strategy;
import com.vertex.model.entity.trading.*;
import com.vertex.model.vo.trading.PositionVO;
import com.vertex.service.order.mapper.PositionMapper;
import com.vertex.service.order.mapper.StrategyRefMapper;
import com.vertex.service.order.service.impl.TradeExecutionService;
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
    private final PositionMapper positionMapper;
    private final StrategyRefMapper strategyRefMapper;
    private final TradeExecutionService tradeExecutionService;

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

    @RequiresPermission("trade:position")
    @Operation(summary = "重新计算并设置止损止盈（用于补录仓位后补设止损）")
    @PostMapping("/{id}/reset-sltp")
    public Result<Void> resetSltp(@PathVariable Long id) {
        Position position = positionMapper.selectById(id);
        if (position == null || position.getStatus() != PositionStatus.OPEN) {
            throw new BizException(GlobalError.POSITION_NOT_FOUND);
        }
        if (position.getStrategyId() == null) {
            throw new BizException(GlobalError.STRATEGY_NOT_FOUND);
        }
        Strategy strategy = strategyRefMapper.selectById(position.getStrategyId());
        if (strategy == null) {
            throw new BizException(GlobalError.STRATEGY_NOT_FOUND);
        }

        // 根据持仓方向构造一个虚拟 Order，供 setStopLossTakeProfit 内部查持仓用
        // LONG 持仓对应 BUY 开仓方向，SHORT 对应 SELL
        Order order = new Order();
        order.setStrategyId(position.getStrategyId());
        order.setAccountId(position.getAccountId());
        order.setExchange(position.getExchange());
        order.setSymbol(position.getSymbol());
        order.setSide(position.getSide() == PositionSide.LONG ? OrderSide.BUY : OrderSide.SELL);
        order.setMarketType(position.getMarketType());
        order.setReduceOnly(false);

        tradeExecutionService.setStopLossTakeProfit(order, strategy);
        return Result.success();
    }
}

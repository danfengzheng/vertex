package com.vertex.admin.web.controller;

import com.vertex.common.core.annotation.RequiresPermission;
import com.vertex.model.vo.trading.ExchangeSymbolVO;
import com.vertex.service.order.cache.ExchangeSymbolCache;
import com.vertex.service.order.task.SymbolSyncTask;
import com.vertex.web.response.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 交易所币对管理
 */
@Tag(name = "交易所币对")
@RestController
@RequestMapping("/admin/trade/symbol")
@RequiredArgsConstructor
public class ExchangeSymbolController {

    private final ExchangeSymbolCache cache;
    private final SymbolSyncTask syncTask;

    /**
     * 查询币对列表（前端下拉数据源）
     *
     * @param exchange   交易所代码，如 binance；为空时返回全部
     * @param marketType 市场类型过滤（可选），如 USDM
     */
    @Operation(summary = "查询币对列表")
    @GetMapping
    public Result<List<ExchangeSymbolVO>> list(
            @RequestParam(required = false) String exchange,
            @RequestParam(required = false) String marketType) {
        List<ExchangeSymbolVO> list = cache.listByExchange(exchange);
        if (marketType != null && !marketType.isEmpty()) {
            list = list.stream()
                    .filter(s -> marketType.equals(s.getMarketType()))
                    .toList();
        }
        // 只返回 TRADING 状态
        list = list.stream()
                .filter(s -> "TRADING".equals(s.getStatus()))
                .toList();
        return Result.success(list);
    }

    /**
     * 手动触发全量同步
     */
    @RequiresPermission("trade:symbol:sync")
    @Operation(summary = "手动同步币对")
    @PostMapping("/sync")
    public Result<Integer> sync() {
        int count = syncTask.syncManual();
        return Result.success(count);
    }
}

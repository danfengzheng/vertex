package com.vertex.service.mm.grid.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.vertex.common.core.annotation.RequiresPermission;
import com.vertex.service.mm.grid.dto.EmergencyStopRequest;
import com.vertex.service.mm.grid.dto.GridStrategyDTO;
import com.vertex.service.mm.grid.dto.StartRequest;
import com.vertex.service.mm.grid.entity.GridEvent;
import com.vertex.service.mm.grid.entity.GridOrder;
import com.vertex.service.mm.grid.entity.GridPnlSnapshot;
import com.vertex.service.mm.grid.entity.GridStrategy;
import com.vertex.service.mm.grid.mapper.GridEventMapper;
import com.vertex.service.mm.grid.mapper.GridOrderMapper;
import com.vertex.service.mm.grid.mapper.GridPnlSnapshotMapper;
import com.vertex.service.mm.grid.backtest.BacktestConfig;
import com.vertex.service.mm.grid.backtest.BacktestResult;
import com.vertex.service.mm.grid.backtest.GridBacktestService;
import com.vertex.service.mm.grid.service.GridStrategyService;
import com.vertex.service.mm.grid.service.SlidingWindowManager;
import com.vertex.service.mm.grid.service.GridState;
import com.vertex.service.mm.grid.vo.GridStatusVO;
import com.vertex.service.mm.market.MarketQuote;
import com.vertex.service.mm.market.MarketQuoteCache;
import com.vertex.service.mm.risk.DepletionState;
import com.vertex.service.mm.risk.TrendScoreEvaluator;
import com.vertex.service.mm.risk.WindowDepletionAnalyzer;
import com.vertex.web.response.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 网格策略 REST 接口。
 * <p>
 * 权限 {@code mm:grid} 由 V29 迁移写进 sys_menu；administrator 默认拥有。
 * </p>
 */
@Tag(name = "做市 - 网格策略")
@RestController
@RequestMapping("/admin/mm/grid")
@RequiredArgsConstructor
public class GridStrategyController {

    private final GridStrategyService service;
    private final GridOrderMapper orderMapper;
    private final GridEventMapper eventMapper;
    private final GridPnlSnapshotMapper snapshotMapper;
    private final SlidingWindowManager slidingWindow;
    private final WindowDepletionAnalyzer depletionAnalyzer;
    private final TrendScoreEvaluator scoreEvaluator;
    private final MarketQuoteCache marketQuoteCache;
    private final GridBacktestService backtestService;

    // ─── CRUD ────────────────────────────────────────────────

    @RequiresPermission("mm:grid")
    @GetMapping
    @Operation(summary = "网格策略列表")
    public Result<List<GridStrategy>> list() {
        return Result.success(service.list());
    }

    @RequiresPermission("mm:grid")
    @GetMapping("/{id}")
    @Operation(summary = "网格策略详情")
    public Result<GridStrategy> get(@PathVariable long id) {
        GridStrategy s = service.get(id);
        if (s == null) return Result.fail(404, "not found");
        return Result.success(s);
    }

    @RequiresPermission("mm:grid")
    @PostMapping
    @Operation(summary = "创建网格策略")
    public Result<Long> create(@RequestBody GridStrategyDTO dto) {
        GridStrategy entity = new GridStrategy();
        BeanUtils.copyProperties(dto, entity);
        Long id = service.create(entity);
        return Result.success(id);
    }

    @RequiresPermission("mm:grid")
    @PutMapping("/{id}")
    @Operation(summary = "更新网格策略参数（热更新）")
    public Result<Void> update(@PathVariable long id, @RequestBody GridStrategyDTO dto) {
        GridStrategy patch = new GridStrategy();
        BeanUtils.copyProperties(dto, patch);
        service.update(id, patch, null);
        return Result.success();
    }

    @RequiresPermission("mm:grid")
    @DeleteMapping("/{id}")
    @Operation(summary = "删除网格策略（须先 stop）")
    public Result<Void> delete(@PathVariable long id) {
        service.delete(id);
        return Result.success();
    }

    // ─── 生命周期 ────────────────────────────────────────────

    @RequiresPermission("mm:grid")
    @PostMapping("/{id}/start")
    @Operation(summary = "启动策略（订阅 WS + 挂满 window）")
    public Result<Void> start(@PathVariable long id, @RequestBody(required = false) StartRequest req) {
        service.start(id, req == null ? null : req.getInitialCenterPrice());
        return Result.success();
    }

    @RequiresPermission("mm:grid")
    @PostMapping("/{id}/stop")
    @Operation(summary = "停止策略（撤所有挂单，保留持仓）")
    public Result<Void> stop(@PathVariable long id) {
        service.stop(id);
        return Result.success();
    }

    @RequiresPermission("mm:grid")
    @PostMapping("/{id}/recover")
    @Operation(summary = "从 EMERGENCY_STOPPED 恢复到 STOPPED（需人工确认）")
    public Result<Void> recover(@PathVariable long id,
                                  @RequestBody(required = false) java.util.Map<String, Object> body) {
        String note = body == null ? null : String.valueOf(body.get("note"));
        service.recoverFromEmergency(id, null, note);
        return Result.success();
    }

    @RequiresPermission("mm:grid")
    @PostMapping("/{id}/emergency")
    @Operation(summary = "紧急停机（SOFT_STOP / GRACEFUL_CLOSE / LIMIT_LADDER_CLOSE / MARKET_FALLBACK）")
    public Result<Void> emergency(@PathVariable long id, @RequestBody EmergencyStopRequest req) {
        String mode = req == null || req.getMode() == null ? "SOFT_STOP" : req.getMode();
        String reason = req == null ? "manual" : req.getReason();
        service.emergencyStop(id, mode, reason);
        return Result.success();
    }

    // ─── 观测 ────────────────────────────────────────────────

    @RequiresPermission("mm:grid")
    @GetMapping("/{id}/status")
    @Operation(summary = "策略实时状态（挂单矩阵 + 保护层 + 趋势雷达）")
    public Result<GridStatusVO> status(@PathVariable long id) {
        GridStrategy strategy = service.get(id);
        if (strategy == null) return Result.fail(404, "not found");

        GridState state = slidingWindow.getState(id);
        int longActive = state == null ? 0 : state.countLongOpenOrders();
        int shortActive = state == null ? 0 : state.countShortOpenOrders();
        int windowSize = state == null ? (strategy.getWindowSizePerSide() == null ? 5
                : strategy.getWindowSizePerSide()) : state.getWindowSizePerSide();

        DepletionState depletion = depletionAnalyzer.compute(id, longActive, shortActive, windowSize);
        scoreEvaluator.evaluate(strategy, depletion, null);

        MarketQuote quote = marketQuoteCache.get(strategy.getSymbol());
        // realtime 字段：state != null 优先取内存（准）；否则 fallback 到 DB（策略未启动或已停止时）
        java.math.BigDecimal rtCenter = state != null && state.getCenterPrice() != null
                ? state.getCenterPrice() : strategy.getCenterPrice();
        java.math.BigDecimal rtStep = state != null && state.getStepPct() != null
                ? state.getStepPct() : strategy.getStepPct();
        java.math.BigDecimal rtLong = state != null ? state.getCurrentLongQty() : strategy.getCurrentLongQty();
        java.math.BigDecimal rtShort = state != null ? state.getCurrentShortQty() : strategy.getCurrentShortQty();

        return Result.success(GridStatusVO.builder()
                .strategy(strategy)
                .realtimeCenterPrice(rtCenter)
                .realtimeStepPct(rtStep)
                .realtimeLongQty(rtLong)
                .realtimeShortQty(rtShort)
                .activeOrders(longActive + shortActive)
                .longActiveOrders(longActive)
                .shortActiveOrders(shortActive)
                .currentLayer(state == null ? "NORMAL" : state.getCurrentLayer().get())
                .depletion(depletion)
                .markPrice(quote == null ? null : quote.mid())
                .spread(quote == null ? null : quote.spread())
                .spreadPct(quote == null ? null : quote.spreadPct())
                .build());
    }

    @RequiresPermission("mm:grid")
    @GetMapping("/{id}/orders")
    @Operation(summary = "策略当前挂单列表")
    public Result<List<GridOrder>> orders(@PathVariable long id,
                                           @RequestParam(defaultValue = "LIVE") String status,
                                           @RequestParam(defaultValue = "100") int limit) {
        var w = new LambdaQueryWrapper<GridOrder>()
                .eq(GridOrder::getGridStrategyId, id)
                .orderByDesc(GridOrder::getCreateTime)
                .last("LIMIT " + Math.max(1, Math.min(1000, limit)));
        if (!"ALL".equalsIgnoreCase(status)) {
            w.eq(GridOrder::getStatus, status);
        }
        return Result.success(orderMapper.selectList(w));
    }

    @RequiresPermission("mm:grid")
    @GetMapping("/{id}/events")
    @Operation(summary = "事件日志")
    public Result<List<GridEvent>> events(@PathVariable long id,
                                           @RequestParam(defaultValue = "200") int limit) {
        return Result.success(eventMapper.selectList(new LambdaQueryWrapper<GridEvent>()
                .eq(GridEvent::getGridStrategyId, id)
                .orderByDesc(GridEvent::getOccurredAt)
                .last("LIMIT " + Math.max(1, Math.min(1000, limit)))));
    }

    @RequiresPermission("mm:grid")
    @PostMapping("/{id}/backtest")
    @Operation(summary = "回测（K 线级 MVP）")
    public Result<BacktestResult> backtest(@PathVariable long id, @RequestBody BacktestConfig config) {
        return Result.success(backtestService.run(id, config));
    }

    @RequiresPermission("mm:grid")
    @GetMapping("/{id}/pnl")
    @Operation(summary = "P&L 快照曲线")
    public Result<List<GridPnlSnapshot>> pnl(@PathVariable long id,
                                              @RequestParam(defaultValue = "500") int limit) {
        return Result.success(snapshotMapper.selectList(new LambdaQueryWrapper<GridPnlSnapshot>()
                .eq(GridPnlSnapshot::getGridStrategyId, id)
                .orderByDesc(GridPnlSnapshot::getSnapshotAt)
                .last("LIMIT " + Math.max(1, Math.min(2000, limit)))));
    }
}

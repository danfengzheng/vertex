package com.vertex.service.farb.controller;

import com.vertex.common.core.annotation.RequiresPermission;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.vertex.service.farb.entity.FarbEventLog;
import com.vertex.service.farb.entity.FarbPosition;
import com.vertex.service.farb.entity.FarbSettlementLog;
import com.vertex.service.farb.entity.FarbStrategy;
import com.vertex.service.farb.execution.ExitTriggerEngine;
import com.vertex.service.farb.execution.FarbRecoveryService;
import com.vertex.service.farb.execution.PairExecutor;
import com.vertex.service.farb.mapper.FarbEventLogMapper;
import com.vertex.service.farb.mapper.FarbPositionMapper;
import com.vertex.service.farb.mapper.FarbSettlementLogMapper;
import com.vertex.service.farb.regime.ArbCandidate;
import com.vertex.service.farb.regime.RegimeMetrics;
import com.vertex.service.farb.regime.RegimeMetricsCalculator;
import com.vertex.service.farb.regime.RegimeScanner;
import com.vertex.service.farb.service.FarbStrategyService;
import com.vertex.service.farb.signal.FarbDataCollector;
import com.vertex.service.farb.signal.FarbSignalCache;
import com.vertex.web.response.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;

/**
 * 资金费套利 - 策略 REST 接口。
 * <p>Sprint 1：CRUD + 生命周期 stub + universe 查看。业务逻辑在 Sprint 2-8 补齐。</p>
 * <p>权限 {@code farb:strategy} 由 V38 迁移写进 sys_menu；administrator 默认拥有。</p>
 */
@Tag(name = "资金费套利 - 策略")
@RestController
@RequestMapping("/admin/farb/strategy")
@RequiredArgsConstructor
public class FarbStrategyController {

    private final FarbStrategyService service;
    private final FarbDataCollector collector;
    private final FarbSignalCache signalCache;
    private final RegimeMetricsCalculator regimeCalc;
    private final RegimeScanner scanner;
    private final FarbEventLogMapper eventLogMapper;
    private final FarbPositionMapper positionMapper;
    private final FarbSettlementLogMapper settlementLogMapper;
    private final PairExecutor pairExecutor;
    private final FarbRecoveryService recoveryService;

    // ─── CRUD ────────────────────────────────────────────────────────────────

    @RequiresPermission("farb:strategy")
    @GetMapping
    @Operation(summary = "策略列表")
    public Result<List<FarbStrategy>> list() {
        return Result.success(service.list());
    }

    @RequiresPermission("farb:strategy")
    @GetMapping("/{id}")
    @Operation(summary = "策略详情")
    public Result<FarbStrategy> get(@PathVariable long id) {
        FarbStrategy s = service.get(id);
        if (s == null) return Result.fail(404, "not found");
        return Result.success(s);
    }

    @RequiresPermission("farb:strategy")
    @PostMapping
    @Operation(summary = "创建策略")
    public Result<Long> create(@RequestBody FarbStrategy dto) {
        return Result.success(service.create(dto));
    }

    @RequiresPermission("farb:strategy")
    @PutMapping("/{id}")
    @Operation(summary = "更新策略参数")
    public Result<Void> update(@PathVariable long id, @RequestBody FarbStrategy dto) {
        FarbStrategy patch = new FarbStrategy();
        BeanUtils.copyProperties(dto, patch);
        service.update(id, patch);
        return Result.success();
    }

    @RequiresPermission("farb:strategy")
    @DeleteMapping("/{id}")
    @Operation(summary = "删除策略（必须 STOPPED）")
    public Result<Void> delete(@PathVariable long id) {
        service.delete(id);
        return Result.success();
    }

    // ─── 生命周期 (Sprint 1 stub) ────────────────────────────────────────────

    @RequiresPermission("farb:strategy")
    @PostMapping("/{id}/start")
    @Operation(summary = "启动策略（Sprint 1: 只改状态，Scanner/Executor 在 Sprint 3+ 实现）")
    public Result<Void> start(@PathVariable long id) {
        service.start(id);
        return Result.success();
    }

    @RequiresPermission("farb:strategy")
    @PostMapping("/{id}/stop")
    @Operation(summary = "停止策略")
    public Result<Void> stop(@PathVariable long id) {
        service.stop(id);
        return Result.success();
    }

    // ─── DataCollector 观测 ─────────────────────────────────────────────────

    @RequiresPermission("farb:strategy")
    @GetMapping("/universe")
    @Operation(summary = "当前可用交集 symbol（现货 ∩ USDM ∩ 24h volume 达标）")
    public Result<Set<String>> universe() {
        return Result.success(collector.getUniverse());
    }

    @RequiresPermission("farb:strategy")
    @GetMapping("/rate-limit-status")
    @Operation(summary = "Binance rate limit 用量监控（USDM weight/min，2400 上限）")
    public Result<java.util.Map<String, Object>> rateLimitStatus() {
        int used = collector.getLatestUsedWeight();
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("usedWeightPerMinute", used);
        m.put("limitPerMinute", 2400);
        m.put("utilizationPct", used * 100.0 / 2400.0);
        m.put("status", used < 1500 ? "OK" : used < 2000 ? "WARNING" : "DANGER");
        return Result.success(m);
    }

    @RequiresPermission("farb:strategy")
    @GetMapping("/signal/{symbol}")
    @Operation(summary = "查看某 symbol 的信号缓存状态（各指标当前样本数）")
    public Result<java.util.Map<String, Integer>> signalStatus(@PathVariable String symbol) {
        java.util.Map<String, Integer> m = new java.util.LinkedHashMap<>();
        m.put("funding", signalCache.fundingSize(symbol));
        m.put("premium", signalCache.premiumSize(symbol));
        m.put("oi",      signalCache.oiSize(symbol));
        m.put("lsr",     signalCache.lsrSize(symbol));
        m.put("tbsr",    signalCache.tbsrSize(symbol));
        m.put("vol",     signalCache.volSize(symbol));
        return Result.success(m);
    }

    @RequiresPermission("farb:strategy")
    @GetMapping("/regime/{symbol}")
    @Operation(summary = "查看某 symbol 的实时 regime 指标（Sprint 2 调试用）")
    public Result<RegimeMetrics> regime(@PathVariable String symbol,
                                         @RequestParam(defaultValue = "6") int lookback,
                                         @RequestParam(defaultValue = "4.0") double minFundingBps) {
        return Result.success(regimeCalc.compute(symbol, lookback, minFundingBps));
    }

    // ─── Sprint 3: 扫描结果 + 事件日志 ─────────────────────────────────────

    @RequiresPermission("farb:strategy")
    @GetMapping("/{id}/candidates")
    @Operation(summary = "查看策略最近一次扫描的候选队列（Sprint 3）")
    public Result<java.util.Map<String, Object>> candidates(@PathVariable long id) {
        List<ArbCandidate> list = scanner.getLatestCandidates(id);
        Long ts = scanner.getLastScanAt(id);
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("lastScanAt", ts);
        m.put("count", list.size());
        m.put("candidates", list);
        return Result.success(m);
    }

    @RequiresPermission("farb:strategy")
    @GetMapping("/{id}/events")
    @Operation(summary = "策略事件日志（按发生时间倒序，最多 N 条）")
    public Result<List<FarbEventLog>> events(@PathVariable long id,
                                              @RequestParam(defaultValue = "200") int limit) {
        return Result.success(eventLogMapper.selectList(new LambdaQueryWrapper<FarbEventLog>()
                .eq(FarbEventLog::getStrategyId, id)
                .orderByDesc(FarbEventLog::getOccurredAt)
                .last("LIMIT " + Math.max(1, Math.min(1000, limit)))));
    }

    // ─── Sprint 4: 持仓 pair 查询 ────────────────────────────────────────

    @RequiresPermission("farb:strategy")
    @GetMapping("/{id}/pairs")
    @Operation(summary = "策略持仓 pair 列表（含各状态）")
    public Result<List<FarbPosition>> pairs(@PathVariable long id,
                                             @RequestParam(defaultValue = "ALL") String status,
                                             @RequestParam(defaultValue = "200") int limit) {
        LambdaQueryWrapper<FarbPosition> w = new LambdaQueryWrapper<FarbPosition>()
                .eq(FarbPosition::getStrategyId, id)
                .orderByDesc(FarbPosition::getEntryTime)
                .last("LIMIT " + Math.max(1, Math.min(1000, limit)));
        if (!"ALL".equalsIgnoreCase(status)) {
            w.eq(FarbPosition::getStatus, status);
        }
        return Result.success(positionMapper.selectList(w));
    }

    // ─── Sprint 5：结算日志 + 手动平仓 ─────────────────────────────────

    @RequiresPermission("farb:strategy")
    @GetMapping("/pair/{pairId}/settlements")
    @Operation(summary = "查看某 pair 的所有结算记录")
    public Result<List<FarbSettlementLog>> settlements(@PathVariable long pairId) {
        return Result.success(settlementLogMapper.selectList(
                new LambdaQueryWrapper<FarbSettlementLog>()
                        .eq(FarbSettlementLog::getPositionId, pairId)
                        .orderByDesc(FarbSettlementLog::getSettlementTime)));
    }

    @RequiresPermission("farb:strategy")
    @PostMapping("/pair/{pairId}/close")
    @Operation(summary = "手动平仓（用户触发）")
    public Result<Void> manualClose(@PathVariable long pairId) {
        FarbPosition pair = positionMapper.selectById(pairId);
        if (pair == null) return Result.fail(404, "pair not found");
        if (!"OPEN".equalsIgnoreCase(pair.getStatus())) {
            return Result.fail(400, "pair status=" + pair.getStatus() + " not OPEN");
        }
        FarbStrategy s = service.get(pair.getStrategyId());
        if (s == null) return Result.fail(404, "strategy not found");
        boolean ok = pairExecutor.closePair(s, pair,
                ExitTriggerEngine.ExitTrigger.MANUAL.name());
        return ok ? Result.success() : Result.fail(500, "close failed, see event log");
    }

    @RequiresPermission("farb:strategy")
    @PostMapping("/pair/{pairId}/force-open")
    @Operation(summary = "崩溃恢复辅助：强制标 EMERGENCY_CLOSED pair 为 OPEN（用户已在币安确认双腿实际成交）")
    public Result<Void> forceOpen(@PathVariable long pairId) {
        recoveryService.forceMarkOpen(pairId);
        return Result.success();
    }
}

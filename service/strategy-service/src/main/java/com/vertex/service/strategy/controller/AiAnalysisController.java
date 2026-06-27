package com.vertex.service.strategy.controller;

import com.vertex.common.core.annotation.RequiresPermission;
import com.vertex.model.entity.strategy.Signal;
import com.vertex.model.entity.strategy.Strategy;
import com.vertex.model.vo.ai.AiBacktestAnalysisProgress;
import com.vertex.model.vo.ai.AiSignalAnalysis;
import com.vertex.model.vo.ai.AiSignalAnalysisRow;
import com.vertex.model.vo.ai.AiTradeAnalysis;
import com.vertex.model.vo.ai.AiTradeAnalysisRow;
import com.vertex.model.vo.strategy.BacktestResultVO;
import com.vertex.service.strategy.ai.AiAnalysisService;
import com.vertex.service.strategy.ai.AiAnalysisStore;
import com.vertex.service.strategy.mapper.SignalMapper;
import com.vertex.service.strategy.mapper.StrategyMapper;
import com.vertex.web.response.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 分析查询接口。
 * <p>
 * 实时信号 AI 分析、回测 AI 分析、回测进度查询。
 * 所有接口都对 AI 软依赖友好（未启用时返回空 / 空数组 / 适当状态）。
 * </p>
 */
@Tag(name = "AI 分析")
@RestController
@RequestMapping("/admin/ai")
@RequiredArgsConstructor
public class AiAnalysisController {

    /** 软依赖：AI 未启用时为 null（运行时通过 isAvailable() 判断）*/
    @Autowired(required = false)
    private AiAnalysisService aiAnalysisService;

    /** 始终注册（缓存层）*/
    @Autowired(required = false)
    private AiAnalysisStore aiAnalysisStore;

    /** 用于重新触发分析时按 strategyId 查策略 */
    @Autowired
    private StrategyMapper strategyMapper;

    /** 用于单信号手动触发 AI 分析时按 signalId 查信号 */
    @Autowired
    private SignalMapper signalMapper;

    // ─── 实时信号 ──────────────────────────────────────────

    @RequiresPermission("strategy:signal")
    @GetMapping("/signal/{signalId}")
    @Operation(summary = "查询单条信号的 AI 分析（实时信号）")
    public Result<AiSignalAnalysis> getSignalAnalysis(@PathVariable Long signalId) {
        if (aiAnalysisStore == null) return Result.success(null);
        return Result.success(aiAnalysisStore.getSignalAnalysis(signalId));
    }

    @RequiresPermission("strategy:signal")
    @PostMapping("/signal/{signalId}/analyze")
    @Operation(summary = "手动触发/重新触发单条信号的 AI 分析（异步，立即返回）")
    public Result<Boolean> analyzeSignal(@PathVariable Long signalId) {
        if (aiAnalysisService == null || !aiAnalysisService.isEnabled()) {
            return Result.fail(400, "AI service not enabled");
        }
        Signal signal = signalMapper.selectById(signalId);
        if (signal == null) {
            return Result.fail(404, "Signal not found");
        }
        Long strategyId = signal.getStrategyId();
        if (strategyId == null) {
            return Result.fail(400, "Signal missing strategyId");
        }
        Strategy strategy = strategyMapper.selectById(strategyId);
        if (strategy == null) {
            return Result.fail(404, "Strategy not found");
        }
        // 清除旧分析（如果有），再异步重跑
        if (aiAnalysisStore != null) {
            aiAnalysisStore.deleteSignalAnalysis(signalId);
        }
        aiAnalysisService.analyzeSignalAsync(strategy, signal);
        return Result.success(true);
    }

    // ─── 回测 AI 分析 ──────────────────────────────────────

    @RequiresPermission("strategy:config")
    @GetMapping("/backtest/{cacheKey}/progress")
    @Operation(summary = "查询回测 AI 批量分析进度")
    public Result<AiBacktestAnalysisProgress> getBacktestProgress(@PathVariable String cacheKey) {
        if (aiAnalysisStore == null) return Result.success(null);
        return Result.success(aiAnalysisStore.getProgress(cacheKey));
    }

    @RequiresPermission("strategy:config")
    @GetMapping("/backtest/{cacheKey}/trades")
    @Operation(summary = "查询回测中所有 trade 的 AI 分析")
    public Result<List<AiTradeAnalysis>> getBacktestTradeAnalyses(@PathVariable String cacheKey) {
        if (aiAnalysisStore == null) return Result.success(Collections.emptyList());
        return Result.success(aiAnalysisStore.listTradeAnalyses(cacheKey));
    }

    @RequiresPermission("strategy:config")
    @GetMapping("/backtest/{cacheKey}/trades/{tradeIndex}")
    @Operation(summary = "查询回测中单笔 trade 的 AI 分析")
    public Result<AiTradeAnalysis> getBacktestTradeAnalysis(@PathVariable String cacheKey,
                                                            @PathVariable Integer tradeIndex) {
        if (aiAnalysisStore == null) return Result.success(null);
        return Result.success(aiAnalysisStore.getTradeAnalysis(cacheKey, tradeIndex));
    }

    @RequiresPermission("strategy:config")
    @PostMapping("/backtest/{cacheKey}/analyze")
    @Operation(summary = "手动触发/重新触发回测 AI 批量分析（不重跑回测）")
    public Result<Boolean> retriggerBacktestAnalysis(@PathVariable String cacheKey) {
        if (aiAnalysisService == null || !aiAnalysisService.isEnabled()) {
            return Result.fail(400, "AI service not enabled");
        }
        if (aiAnalysisStore == null) {
            return Result.fail(400, "AI store not available");
        }
        BacktestResultVO cached = aiAnalysisService.getBacktestResult(cacheKey);
        if (cached == null) {
            return Result.fail(404, "Backtest cache not found");
        }
        Long strategyId = cached.getStrategyId();
        if (strategyId == null) {
            return Result.fail(400, "Cached result missing strategyId");
        }
        Strategy strategy = strategyMapper.selectById(strategyId);
        if (strategy == null) {
            return Result.fail(404, "Strategy not found");
        }
        // 删除旧 trade 分析数据（清理后重新生成）
        aiAnalysisStore.deleteAllTradeAnalyses(cacheKey);
        boolean ok = aiAnalysisService.retriggerBacktestAnalysis(cacheKey, strategy, cached);
        return Result.success(ok);
    }

    @RequiresPermission("strategy:config")
    @DeleteMapping("/backtest/{cacheKey}")
    @Operation(summary = "清除指定回测的缓存与 AI 分析")
    public Result<Integer> clearBacktestCache(@PathVariable String cacheKey) {
        if (aiAnalysisStore == null) return Result.success(0);
        int deletedTrades = aiAnalysisStore.deleteAllTradeAnalyses(cacheKey);
        aiAnalysisStore.deleteBacktestResult(cacheKey);
        aiAnalysisStore.deleteProgress(cacheKey);
        return Result.success(deletedTrades);
    }

    // ─── 仪表盘列表查询 ──────────────────────────────────────

    @RequiresPermission("strategy:signal")
    @GetMapping("/dashboard/signals")
    @Operation(summary = "AI 仪表盘：列出最近的实时信号 AI 分析（按 analyzedAt 降序）")
    public Result<List<AiSignalAnalysisRow>> listSignalAnalyses(
            @RequestParam(defaultValue = "100") Integer limit) {
        if (aiAnalysisStore == null) {
            return Result.success(Collections.emptyList());
        }
        int cap = limit == null ? 100 : Math.max(1, Math.min(limit, 500));
        List<Map.Entry<Long, AiSignalAnalysis>> raw = aiAnalysisStore.listSignalAnalyses(cap);
        if (raw.isEmpty()) {
            return Result.success(Collections.emptyList());
        }
        // 批量查 Signal 表补 context
        List<Long> signalIds = new ArrayList<>(raw.size());
        for (Map.Entry<Long, AiSignalAnalysis> e : raw) signalIds.add(e.getKey());
        Map<Long, Signal> signalMap = new HashMap<>(signalIds.size());
        for (Signal s : signalMapper.selectBatchIds(signalIds)) {
            if (s.getId() != null) signalMap.put(s.getId(), s);
        }
        List<AiSignalAnalysisRow> rows = new ArrayList<>(raw.size());
        for (Map.Entry<Long, AiSignalAnalysis> e : raw) {
            Signal s = signalMap.get(e.getKey());
            AiSignalAnalysisRow row = AiSignalAnalysisRow.builder()
                    .signalId(e.getKey())
                    .analysis(e.getValue())
                    .build();
            if (s != null) {
                row.setStrategyId(s.getStrategyId());
                row.setStrategyName(s.getStrategyName());
                row.setExchange(s.getExchange());
                row.setSymbol(s.getSymbol());
                row.setInterval(s.getInterval() != null ? s.getInterval().name() : null);
                row.setSignalType(s.getSignalType() != null ? s.getSignalType().name() : null);
                row.setSignalStrength(s.getSignalStrength());
                row.setSignalTime(s.getSignalTime());
                row.setPrice(s.getPrice() != null ? s.getPrice().toPlainString() : null);
            }
            rows.add(row);
        }
        return Result.success(rows);
    }

    @RequiresPermission("strategy:signal")
    @GetMapping("/dashboard/trades")
    @Operation(summary = "AI 仪表盘：列出最近的回测 Trade AI 分析（跨所有回测，按 analyzedAt 降序）")
    public Result<List<AiTradeAnalysisRow>> listTradeAnalyses(
            @RequestParam(defaultValue = "100") Integer limit) {
        if (aiAnalysisStore == null) {
            return Result.success(Collections.emptyList());
        }
        int cap = limit == null ? 100 : Math.max(1, Math.min(limit, 500));
        List<AiAnalysisStore.TradeAnalysisEntry> raw = aiAnalysisStore.listAllTradeAnalyses(cap);
        if (raw.isEmpty()) {
            return Result.success(Collections.emptyList());
        }
        // 缓存：同一 cacheKey 只查一次回测结果
        Map<String, BacktestResultVO> backtestMap = new HashMap<>();
        List<AiTradeAnalysisRow> rows = new ArrayList<>(raw.size());
        for (AiAnalysisStore.TradeAnalysisEntry e : raw) {
            AiTradeAnalysisRow row = AiTradeAnalysisRow.builder()
                    .cacheKey(e.cacheKey)
                    .tradeIndex(e.tradeIndex)
                    .analysis(e.analysis)
                    .build();
            BacktestResultVO bt = backtestMap.computeIfAbsent(e.cacheKey,
                    aiAnalysisStore::getBacktestResult);
            if (bt != null) {
                row.setStrategyId(bt.getStrategyId());
                row.setStrategyName(bt.getStrategyName());
                if (bt.getTrades() != null
                        && e.tradeIndex >= 0
                        && e.tradeIndex < bt.getTrades().size()) {
                    BacktestResultVO.TradeRecord tr = bt.getTrades().get(e.tradeIndex);
                    row.setEntryTime(tr.getEntryTime());
                    row.setExitTime(tr.getExitTime());
                    row.setType(tr.getType());
                    row.setProfit(toStr(tr.getProfit()));
                    row.setProfitPercent(toStr(tr.getProfitPercent()));
                }
                // exchange / symbol / interval 没存在 BacktestResultVO 里；
                // 再查 Strategy 兜底（同一 cacheKey 只查一次）
                if (bt.getStrategyId() != null) {
                    Strategy st = strategyMapper.selectById(bt.getStrategyId());
                    if (st != null) {
                        row.setExchange(st.getExchange());
                        row.setSymbol(st.getSymbol());
                        row.setInterval(st.getInterval() != null ? st.getInterval().name() : null);
                    }
                }
            }
            rows.add(row);
        }
        return Result.success(rows);
    }

    private static String toStr(BigDecimal v) {
        return v == null ? null : v.toPlainString();
    }

    // ─── 运行时状态 ────────────────────────────────────────

    @GetMapping("/status")
    @Operation(summary = "查询 AI 模块运行时状态：provider / model / 线程池堆积")
    public Result<AiStatusVO> status() {
        AiStatusVO vo = new AiStatusVO();
        vo.cacheEnabled = aiAnalysisStore != null;
        vo.aiEnabled = aiAnalysisService != null && aiAnalysisService.isEnabled();
        if (aiAnalysisService != null) {
            vo.provider = aiAnalysisService.currentProvider();
            vo.model = aiAnalysisService.currentModel();
            vo.workerThreads = aiAnalysisService.workerThreads();
            vo.queueCapacity = aiAnalysisService.queueCapacity();
            vo.queueSize = aiAnalysisService.queueSize();
            vo.activeCount = aiAnalysisService.activeCount();
            vo.completedTaskCount = aiAnalysisService.completedTaskCount();
            vo.rejectedTaskCount = aiAnalysisService.rejectedTaskCount();
        }
        return Result.success(vo);
    }

    /**
     * 运行时状态 VO：provider / model 用于核验配置生效，
     * 线程池字段用于排查队列堆积、丢弃任务等异常。
     */
    public static class AiStatusVO {
        public boolean cacheEnabled;
        public boolean aiEnabled;
        /** 实际生效的 provider：gemini / deepseek / null */
        public String provider;
        /** 实际生效的模型名 */
        public String model;
        /** 工作线程数 */
        public int workerThreads;
        /** 队列容量 */
        public int queueCapacity;
        /** 当前队列里 pending 任务数 */
        public int queueSize;
        /** 当前正在执行的任务数 */
        public int activeCount;
        /** 累计完成任务数 */
        public long completedTaskCount;
        /** 累计被丢弃的任务数（队列满拒绝） */
        public long rejectedTaskCount;
    }
}

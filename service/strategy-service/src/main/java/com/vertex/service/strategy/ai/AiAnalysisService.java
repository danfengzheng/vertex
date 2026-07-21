package com.vertex.service.strategy.ai;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.vertex.model.dto.strategy.StrategyIndicatorConfig;
import com.vertex.model.entity.quote.KLine;
import com.vertex.model.entity.quote.KLineInterval;
import com.vertex.model.entity.strategy.Signal;
import com.vertex.model.entity.strategy.SignalType;
import com.vertex.model.entity.strategy.Strategy;
import com.vertex.service.strategy.engine.IndicatorCalculationEngine;
import com.vertex.service.strategy.notify.SignalTelegramNotifier;
import com.vertex.model.vo.ai.AiBacktestAnalysisProgress;
import com.vertex.model.vo.ai.AiSignalAnalysis;
import com.vertex.model.vo.ai.AiTradeAnalysis;
import com.vertex.model.vo.strategy.BacktestResultVO;
import com.vertex.service.quote.store.KLineStore;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * AI 分析服务（异步）：
 * <ul>
 *   <li>实时信号触发后异步分析 → 写入 {@link AiAnalysisStore}</li>
 *   <li>回测完成后批量分析所有 trades → 写入并维护进度</li>
 * </ul>
 * <p>
 * 仅在 {@link AiClient} 至少有一个实现注册时才工作（即 {@link GeminiClient} 或
 * {@link DeepSeekClient} 之一被激活）。客户端未注册（含本组件本身均为可选注入）→
 * 接口降级为 no-op，主流程不受影响。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiAnalysisService {

    private final AiProperties aiProperties;
    private final AiConfigService configService;
    private final AiAnalysisStore store;
    private final KLineStore klineStore;
    /**
     * 指标引擎：用于计算策略所有指标在主周期上的 requiredDataPoints 最大值，
     * 让 AI 的上下文 K 线窗口刚好覆盖所有指标稳定所需的历史长度。
     */
    private final IndicatorCalculationEngine indicatorCalculationEngine;

    /**
     * 软依赖：未启用任何 provider 时为 null，本服务所有方法降级 no-op。
     * 通过 @ConditionalOnProperty + @ConditionalOnExpression 在
     * GeminiClient / DeepSeekClient 中只会注册一个实现。
     */
    @Autowired(required = false)
    private AiClient aiClient;

    /**
     * 软依赖：未启用 Telegram 时为 null。
     * AI 分析完成后通过它向用户的 Telegram 追加一条精简 AI 摘要。
     */
    @Autowired(required = false)
    private SignalTelegramNotifier signalTelegramNotifier;

    private ThreadPoolExecutor executor;
    private final AtomicLong rejectedCount = new AtomicLong();

    @PostConstruct
    public void init() {
        if (aiClient == null) {
            log.info("[AiAnalysisService] No AI provider registered (vertex.ai.provider=?), all AI analysis will be skipped");
            return;
        }
        int threads = Math.max(1, aiProperties.getWorkerThreads());
        int capacity = Math.max(100, aiProperties.getQueueCapacity());
        this.executor = new ThreadPoolExecutor(
                threads, threads,
                60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(capacity),
                r -> {
                    Thread t = new Thread(r, "ai-analysis-worker");
                    t.setDaemon(true);
                    return t;
                },
                (r, exec) -> {
                    long c = rejectedCount.incrementAndGet();
                    log.warn("[AiAnalysisService] queue full ({}), task rejected (total rejected: {})",
                            capacity, c);
                }
        );
        log.info("[AiAnalysisService] activated: provider={}, model={}, threads={}, queueCapacity={}",
                aiClient.providerName(), aiClient.currentModel(), threads, capacity);
    }

    @PreDestroy
    public void destroy() {
        if (executor != null) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * AI 服务是否可用。
     * <p>
     * 双开关：yaml bean 级 {@code vertex.ai.enabled=true}（决定 aiClient bean 是否注入）
     * + DB 里的 {@code ai_config.enabled=1}（业务运行开关，UI 可热切换）。
     * </p>
     */
    public boolean isEnabled() {
        if (aiClient == null) return false;
        try {
            Integer dbEnabled = configService.get().getEnabled();
            return dbEnabled != null && dbEnabled == 1;
        } catch (Exception e) {
            // DB 读失败视为不可用，避免死等
            return false;
        }
    }

    /** 当前实际使用的 provider 名（gemini / deepseek）；未启用时返回 null */
    public String currentProvider() {
        return aiClient == null ? null : aiClient.providerName();
    }

    /** 当前实际使用的模型名（动态从 AiProperties 读取，配置改了重启即生效） */
    public String currentModel() {
        return aiClient == null ? null : aiClient.currentModel();
    }

    /** 线程池工作线程数（配置值）。executor 未启动时返回 0 */
    public int workerThreads() {
        return executor == null ? 0 : executor.getMaximumPoolSize();
    }

    /** 任务队列容量（配置值）。executor 未启动时返回 0 */
    public int queueCapacity() {
        if (executor == null) return 0;
        var queue = executor.getQueue();
        return queue.size() + queue.remainingCapacity();
    }

    /** 当前队列堆积（pending 任务数）。executor 未启动时返回 0 */
    public int queueSize() {
        return executor == null ? 0 : executor.getQueue().size();
    }

    /** 当前正在执行的任务数。executor 未启动时返回 0 */
    public int activeCount() {
        return executor == null ? 0 : executor.getActiveCount();
    }

    /** 累计完成的任务数。executor 未启动时返回 0 */
    public long completedTaskCount() {
        return executor == null ? 0L : executor.getCompletedTaskCount();
    }

    /** 累计被丢弃的任务数（队列满时直接拒绝）。 */
    public long rejectedTaskCount() {
        return rejectedCount.get();
    }

    // ─── 实时信号分析 ──────────────────────────────────────────

    /**
     * 异步分析单条信号（非 NEUTRAL）→ 写入 RocksDB。
     * 主流程调用方不阻塞，失败仅记录日志不影响信号入库/推送。
     */
    public void analyzeSignalAsync(Strategy strategy, Signal signal) {
        if (executor == null) return;
        // DB 里的业务开关 —— 关掉则跳过，不占用线程/API 调用
        if (!isEnabled()) return;
        try {
            executor.submit(() -> {
                try {
                    AiSignalAnalysis analysis = analyzeSignalSync(strategy, signal);
                    if (signal.getId() != null) {
                        store.saveSignalAnalysis(signal.getId(), analysis);
                    }
                    // 追加 Telegram 通知：AI 完成后向同一用户发简短摘要（complementary 消息）
                    sendAiSummaryToTelegram(signal, analysis);
                } catch (Throwable t) {
                    log.warn("[AI] Signal analysis failed: signalId={}, err={}",
                            signal.getId(), t.getMessage());
                }
            });
        } catch (Exception e) {
            log.warn("[AI] submit signal analysis failed: {}", e.getMessage());
        }
    }

    /**
     * AI 分析完成后向 Telegram 推送精简摘要，与原信号通知互补：
     * <ul>
     *   <li>signalTelegramNotifier 未注册（vertex.strategy.telegram.enabled=false）→ 跳过</li>
     *   <li>AI 分析失败（errorMessage 非空）→ 跳过</li>
     *   <li>NEUTRAL 信号永不发（这里调用方已过滤）</li>
     * </ul>
     */
    private void sendAiSummaryToTelegram(Signal signal, AiSignalAnalysis analysis) {
        if (signalTelegramNotifier == null) return;
        if (analysis == null || analysis.getErrorMessage() != null) return;
        if (signal.getSignalType() == SignalType.NEUTRAL) return;
        try {
            int conf = analysis.getConfidence() == null ? 0
                    : (int) Math.round(analysis.getConfidence() * 100);
            StringBuilder sb = new StringBuilder(512);
            sb.append("🤖 <b>AI 分析</b>（置信度 ").append(conf).append("%）\n");
            if (analysis.getAlignment() != null) {
                sb.append("方向: ").append(escapeHtml(analysis.getAlignment())).append('\n');
            }
            if (analysis.getMarketRegime() != null) {
                sb.append("市场: ").append(escapeHtml(analysis.getMarketRegime())).append('\n');
            }
            if (analysis.getSuggestedAction() != null) {
                sb.append("建议: <code>").append(escapeHtml(analysis.getSuggestedAction())).append("</code>\n");
            }
            if (analysis.getSummary() != null && !analysis.getSummary().isBlank()) {
                sb.append(escapeHtml(analysis.getSummary())).append('\n');
            }
            if (analysis.getRisks() != null && !analysis.getRisks().isEmpty()) {
                sb.append("\n<b>⚠ 风险:</b>\n");
                for (String r : analysis.getRisks()) {
                    sb.append("• ").append(escapeHtml(r)).append('\n');
                }
            }
            signalTelegramNotifier.sendCustomMessage(signal.getCreateBy(), sb.toString());
        } catch (Exception e) {
            log.warn("[AI] send AI summary to Telegram failed: signalId={}, err={}",
                    signal.getId(), e.getMessage());
        }
    }

    /** Telegram HTML 转义（与 SignalTelegramNotifier 同样规则）*/
    private static String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    // ─── K 线上下文窗口计算 ────────────────────────────────────────────

    /** 默认 K 线数量（策略无指标 / 解析失败时回退） */
    private static final int DEFAULT_AI_CONTEXT_BARS = 30;
    /** 上下文 K 线最少给 30 根，否则 AI 看不到足够趋势 */
    private static final int MIN_AI_CONTEXT_BARS = 30;
    /** 上下文 K 线最多给 200 根，避免 prompt token 暴涨；Gemini 1M 上下文足够，
     *  但 200 根 + 指标值 + schema 通常已经 ~3000-5000 token，再多收益边际递减 */
    private static final int MAX_AI_CONTEXT_BARS = 200;

    /**
     * 计算 AI 上下文需要的主周期 K 线数量。
     * <p>
     * 取策略所有指标在主周期上的 {@code requiredDataPoints} 最大值，
     * 让 AI 看到的 K 线窗口至少覆盖所有指标稳定计算的历史长度。
     * <ul>
     *   <li>多周期指标：只取主周期那个指标的 required；非主周期不计入</li>
     *   <li>结果 + 5 根 buffer（保留近期上下文 K 线供 AI 看趋势收尾）</li>
     *   <li>cap 在 [MIN_AI_CONTEXT_BARS, MAX_AI_CONTEXT_BARS] 之间</li>
     * </ul>
     * </p>
     */
    private int computeContextBarCount(Strategy strategy) {
        try {
            List<StrategyIndicatorConfig> configs = JSON.parseArray(
                    strategy.getIndicatorConfigs(), StrategyIndicatorConfig.class);
            if (configs == null || configs.isEmpty()) {
                return DEFAULT_AI_CONTEXT_BARS;
            }
            java.util.Map<KLineInterval, Integer> maxRequired =
                    indicatorCalculationEngine.computeMaxRequired(strategy, configs);
            Integer mainReq = maxRequired.get(strategy.getInterval());
            if (mainReq == null || mainReq <= 0) {
                return DEFAULT_AI_CONTEXT_BARS;
            }
            int target = mainReq + 5;
            return Math.max(MIN_AI_CONTEXT_BARS, Math.min(MAX_AI_CONTEXT_BARS, target));
        } catch (Exception e) {
            log.warn("[AI] computeContextBarCount failed, fallback to {}: {}",
                    DEFAULT_AI_CONTEXT_BARS, e.getMessage());
            return DEFAULT_AI_CONTEXT_BARS;
        }
    }

    /**
     * 同步分析信号（异步路径内部使用，也对外暴露给「立即分析」按钮）。
     */
    public AiSignalAnalysis analyzeSignalSync(Strategy strategy, Signal signal) {
        long t0 = System.currentTimeMillis();
        try {
            // K 线数量：按主周期下所有指标的 requiredDataPoints 最大值动态决定，
            // 上下限 cap 在 [30, 200] 之间，避免 prompt 过短没参考意义 / 过长 token 暴涨
            int barCount = computeContextBarCount(strategy);
            long endTime = signal.getSignalTime() != null
                    ? signal.getSignalTime() + strategy.getInterval().getMillis()
                    : System.currentTimeMillis();
            // 多预取 5 根做 buffer，klineStore.query 还是取最近 barCount 条
            long startTime = endTime - strategy.getInterval().getMillis() * (barCount + 5);
            List<KLine> klines = klineStore.query(strategy.getExchange(), strategy.getSymbol(),
                    strategy.getInterval(), startTime, endTime, barCount, true);

            AiPromptBuilder.PromptParts prompt = AiPromptBuilder.buildSignalPromptParts(
                    strategy, signal, klines, configService.get().getLanguage());
            JSONObject schema = AiPromptBuilder.buildSignalSchema();

            JSONObject json = aiClient.generateJson(prompt.systemPrompt, prompt.userPrompt, schema);
            // fastjson2: 用 toJSONString 中转，兼容性最稳（避免依赖 JSON.to(Class, Object) 这种较新 API）
            AiSignalAnalysis analysis = JSON.parseObject(json.toJSONString(), AiSignalAnalysis.class);
            if (analysis == null) {
                analysis = AiSignalAnalysis.builder().build();
            }
            analysis.setModel(aiClient.currentModel());
            analysis.setAnalyzedAt(System.currentTimeMillis());
            analysis.setDurationMs(System.currentTimeMillis() - t0);
            return analysis;
        } catch (Exception e) {
            return AiSignalAnalysis.builder()
                    .model(aiClient.currentModel())
                    .analyzedAt(System.currentTimeMillis())
                    .durationMs(System.currentTimeMillis() - t0)
                    .errorMessage(e.getMessage())
                    .build();
        }
    }

    // ─── 回测批量分析 ──────────────────────────────────────────

    /**
     * 异步对回测结果中所有 trades 批量分析。
     * 进度持续写入 RocksDB（前端可轮询）。
     */
    public void analyzeBacktestBatchAsync(String cacheKey, Strategy strategy, BacktestResultVO result) {
        if (executor == null || result == null || result.getTrades() == null || result.getTrades().isEmpty()) {
            return;
        }
        if (!isEnabled()) return;   // DB 业务开关
        try {
            executor.submit(() -> runBatchAnalysis(cacheKey, strategy, result));
        } catch (Exception e) {
            log.warn("[AI] submit backtest batch analysis failed: cacheKey={}, err={}", cacheKey, e.getMessage());
        }
    }

    private void runBatchAnalysis(String cacheKey, Strategy strategy, BacktestResultVO result) {
        List<BacktestResultVO.TradeRecord> trades = result.getTrades();
        int total = trades.size();
        long t0 = System.currentTimeMillis();

        AiBacktestAnalysisProgress progress = AiBacktestAnalysisProgress.builder()
                .cacheKey(cacheKey)
                .strategyId(strategy.getId())
                .strategyName(strategy.getName())
                .total(total)
                .completed(0)
                .failed(0)
                .status(AiBacktestAnalysisProgress.Status.RUNNING)
                .startedAt(t0)
                .updatedAt(t0)
                .build();
        store.saveProgress(progress);
        log.info("[AI] backtest batch start: cacheKey={}, total={}", cacheKey, total);

        int completed = 0;
        int failed = 0;
        for (int idx = 0; idx < trades.size(); idx++) {
            BacktestResultVO.TradeRecord trade = trades.get(idx);
            try {
                AiTradeAnalysis analysis = analyzeTradeSync(strategy, trade, idx, total);
                analysis.setTradeIndex(idx);
                analysis.setEntryTime(trade.getEntryTime());
                analysis.setExitTime(trade.getExitTime());
                store.saveTradeAnalysis(cacheKey, idx, analysis);
                if (analysis.getErrorMessage() != null) {
                    failed++;
                } else {
                    completed++;
                }
            } catch (Throwable t) {
                failed++;
                log.warn("[AI] trade analysis failed: cacheKey={}, idx={}, err={}",
                        cacheKey, idx, t.getMessage());
            }
            // 每 5 笔或末尾，更新进度
            if ((idx + 1) % 5 == 0 || idx == trades.size() - 1) {
                progress.setCompleted(completed);
                progress.setFailed(failed);
                progress.setUpdatedAt(System.currentTimeMillis());
                store.saveProgress(progress);
            }
        }
        progress.setCompleted(completed);
        progress.setFailed(failed);
        progress.setStatus(AiBacktestAnalysisProgress.Status.COMPLETED);
        progress.setCompletedAt(System.currentTimeMillis());
        progress.setUpdatedAt(progress.getCompletedAt());
        store.saveProgress(progress);

        log.info("[AI] backtest batch done: cacheKey={}, completed={}/{}, failed={}, elapsed={}ms",
                cacheKey, completed, total, failed, System.currentTimeMillis() - t0);
    }

    private AiTradeAnalysis analyzeTradeSync(Strategy strategy,
                                              BacktestResultVO.TradeRecord trade,
                                              int idx, int total) {
        long t0 = System.currentTimeMillis();
        try {
            KLineInterval iv = strategy.getInterval();
            // 同样按指标 requiredDataPoints 动态决定窗口；trade 是事后分析，
            // 时间窗口围绕 entry 居中（前 2/3 是入场前，后 1/3 是入场后），
            // 让 AI 既看到入场上下文也看到走势如何展开
            int barCount = computeContextBarCount(strategy);
            long around = trade.getEntryTime() != null ? trade.getEntryTime() : System.currentTimeMillis();
            long startTime = around - iv.getMillis() * (barCount * 2 / 3);
            long endTime   = around + iv.getMillis() * (barCount / 3);
            List<KLine> klines = klineStore.query(strategy.getExchange(), strategy.getSymbol(),
                    iv, startTime, endTime, barCount, true);

            AiPromptBuilder.PromptParts prompt = AiPromptBuilder.buildTradePromptParts(
                    strategy, trade, idx, total, klines, configService.get().getLanguage());
            JSONObject schema = AiPromptBuilder.buildTradeSchema();

            JSONObject json = aiClient.generateJson(prompt.systemPrompt, prompt.userPrompt, schema);
            AiTradeAnalysis analysis = JSON.parseObject(json.toJSONString(), AiTradeAnalysis.class);
            if (analysis == null) analysis = AiTradeAnalysis.builder().build();
            analysis.setModel(aiClient.currentModel());
            analysis.setAnalyzedAt(System.currentTimeMillis());
            analysis.setDurationMs(System.currentTimeMillis() - t0);
            return analysis;
        } catch (Exception e) {
            return AiTradeAnalysis.builder()
                    .tradeIndex(idx)
                    .model(aiClient.currentModel())
                    .analyzedAt(System.currentTimeMillis())
                    .durationMs(System.currentTimeMillis() - t0)
                    .errorMessage(e.getMessage())
                    .build();
        }
    }

    /**
     * 手动重新触发 backtest 批量 AI 分析（不重跑回测）。
     * <p>
     * 用户场景：缓存命中但首次未启用 AI，事后想补一份 AI；或之前失败想重试。
     * 复用 RocksDB 中已有的 BacktestResultVO + Strategy（按 strategyId 查 DB）。
     * </p>
     *
     * @return true=成功提交；false=未启用 / 缓存不存在 / 已在分析中
     */
    public boolean retriggerBacktestAnalysis(String cacheKey,
                                              com.vertex.model.entity.strategy.Strategy strategy,
                                              com.vertex.model.vo.strategy.BacktestResultVO cached) {
        if (executor == null) return false;
        if (cached == null || cached.getTrades() == null || cached.getTrades().isEmpty()) return false;
        AiBacktestAnalysisProgress prog = store.getProgress(cacheKey);
        if (prog != null && (prog.getStatus() == AiBacktestAnalysisProgress.Status.RUNNING
                || prog.getStatus() == AiBacktestAnalysisProgress.Status.PENDING)) {
            return false;
        }
        AiBacktestAnalysisProgress initial = AiBacktestAnalysisProgress.builder()
                .cacheKey(cacheKey)
                .strategyId(strategy.getId())
                .strategyName(strategy.getName())
                .total(cached.getTrades().size())
                .completed(0)
                .failed(0)
                .status(AiBacktestAnalysisProgress.Status.PENDING)
                .startedAt(System.currentTimeMillis())
                .updatedAt(System.currentTimeMillis())
                .build();
        store.saveProgress(initial);
        analyzeBacktestBatchAsync(cacheKey, strategy, cached);
        return true;
    }

    // ─── 查询代理 ─────────────────────────────────────────────

    public AiSignalAnalysis getSignalAnalysis(long signalId) {
        return store.getSignalAnalysis(signalId);
    }

    public List<AiTradeAnalysis> listTradeAnalyses(String cacheKey) {
        return store.listTradeAnalyses(cacheKey);
    }

    public AiBacktestAnalysisProgress getProgress(String cacheKey) {
        return store.getProgress(cacheKey);
    }

    public com.vertex.model.vo.strategy.BacktestResultVO getBacktestResult(String cacheKey) {
        return store.getBacktestResult(cacheKey);
    }
}

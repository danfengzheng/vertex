package com.vertex.service.strategy.service.impl;

import com.vertex.model.entity.strategy.SignalCleanupConfig;
import com.vertex.model.vo.strategy.SignalCleanupPreviewVO;
import com.vertex.model.vo.strategy.SignalCleanupRunResultVO;
import com.vertex.service.strategy.ai.AiAnalysisStore;
import com.vertex.service.strategy.mapper.SignalCleanupMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 信号清理引擎。
 * <p>
 * 分级 TTL（NEUTRAL / BUY-SELL 未关联 / BUY-SELL 已关联）+ 保护期，
 * 支持 SOFT（默认，@TableLogic 自动过滤）和 HARD（物理 DELETE + RocksDB 级联）两种模式。
 * </p>
 * <p>
 * 触发方式：
 * <ul>
 *   <li>定时任务：每分钟 tick，根据 DB 配置 cron 判断是否到点</li>
 *   <li>手动 API：{@link #runCleanup(String)}</li>
 * </ul>
 * 用 {@link ReentrantLock} 保证同一时刻只有一次清理在跑（tryLock，跳过并发触发）。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SignalCleanupService {

    private static final int MAX_BATCH_ITERATIONS = 200;   // 每类最多循环 200 批，避免死循环
    private static final long DAY_MS = 24L * 3600L * 1000L;

    private final SignalCleanupConfigService configService;
    private final SignalCleanupMapper cleanupMapper;

    /** AI 分析可选注入（vertex.ai.enabled=false 时为 null，硬删除时跳过级联） */
    @Autowired(required = false)
    private AiAnalysisStore aiAnalysisStore;

    /** 同一时刻只允许一次清理 */
    private final ReentrantLock runLock = new ReentrantLock();

    /** 最近一次 cron 触发时间（避免同一分钟内重复触发） */
    private final AtomicReference<LocalDateTime> lastCronFireAt = new AtomicReference<>(null);

    // ─── 预览：只算数，不删 ───────────────────────────────────

    public SignalCleanupPreviewVO preview() {
        SignalCleanupConfig cfg = configService.get();
        long now = System.currentTimeMillis();
        long protectCutoff = now - safeDays(cfg.getProtectRecentDays(), 3) * DAY_MS;

        Long neutralCutoff       = cutoffFor(cfg.getKeepNeutralDays(),     now, protectCutoff);
        Long directionalCutoff   = cutoffFor(cfg.getKeepDirectionalDays(), now, protectCutoff);
        Long linkedCutoff        = cutoffFor(cfg.getKeepLinkedDays(),      now, protectCutoff);

        long willNeutral       = neutralCutoff     == null ? 0 : cleanupMapper.countNeutralOlderThan(neutralCutoff);
        long willDirectional   = directionalCutoff == null ? 0 : cleanupMapper.countDirectionalOrphanOlderThan(directionalCutoff);
        long willLinked        = linkedCutoff      == null ? 0 : cleanupMapper.countLinkedOlderThan(linkedCutoff);
        long willTotal         = willNeutral + willDirectional + willLinked;

        long totalActive       = cleanupMapper.countAllActive();
        long afterCleanup      = Math.max(0L, totalActive - willTotal);

        return SignalCleanupPreviewVO.builder()
                .totalActive(totalActive)
                .willDeleteNeutral(willNeutral)
                .willDeleteDirectionalOrphan(willDirectional)
                .willDeleteLinked(willLinked)
                .willDeleteTotal(willTotal)
                .afterCleanup(afterCleanup)
                .neutralCutoffMs(neutralCutoff)
                .directionalCutoffMs(directionalCutoff)
                .linkedCutoffMs(linkedCutoff)
                .protectCutoffMs(protectCutoff)
                .build();
    }

    // ─── 执行 ────────────────────────────────────────────────

    /**
     * 手动/定时统一入口。
     * <ul>
     *   <li>拿不到锁：直接返回 skipped 结果，errorMessage="already running"</li>
     *   <li>enabled=0 且非手动：直接返回 skipped，"disabled"</li>
     * </ul>
     */
    public SignalCleanupRunResultVO runCleanup(String trigger) {
        long startedAt = System.currentTimeMillis();
        boolean manual = "MANUAL".equalsIgnoreCase(trigger);
        SignalCleanupConfig cfg = configService.get();

        // 定时触发要看 enabled；手动触发允许强制跑一次
        if (!manual && (cfg.getEnabled() == null || cfg.getEnabled() != 1)) {
            return skipped(trigger, cfg.getDeleteMode(), startedAt, "disabled");
        }

        if (!runLock.tryLock()) {
            log.warn("[SignalCleanup] previous run still in progress, skip trigger={}", trigger);
            return skipped(trigger, cfg.getDeleteMode(), startedAt, "already running");
        }

        try {
            long now = System.currentTimeMillis();
            long protectCutoff = now - safeDays(cfg.getProtectRecentDays(), 3) * DAY_MS;
            int batchSize = safeBatch(cfg.getBatchSize());
            String mode = "HARD".equalsIgnoreCase(cfg.getDeleteMode()) ? "HARD" : "SOFT";

            Long neutralCutoff     = cutoffFor(cfg.getKeepNeutralDays(),     now, protectCutoff);
            Long directionalCutoff = cutoffFor(cfg.getKeepDirectionalDays(), now, protectCutoff);
            Long linkedCutoff      = cutoffFor(cfg.getKeepLinkedDays(),      now, protectCutoff);

            log.info("[SignalCleanup] START trigger={}, mode={}, batch={}, cutoffs neutral={} directional={} linked={} protect={}",
                    trigger, mode, batchSize, neutralCutoff, directionalCutoff, linkedCutoff, protectCutoff);

            long delNeutral = 0, delDirectional = 0, delLinked = 0, aiCascade = 0;
            String err = null;

            try {
                if (neutralCutoff != null) {
                    long[] r = deleteCategory(Category.NEUTRAL, neutralCutoff, batchSize, mode);
                    delNeutral = r[0]; aiCascade += r[1];
                }
                if (directionalCutoff != null) {
                    long[] r = deleteCategory(Category.DIRECTIONAL_ORPHAN, directionalCutoff, batchSize, mode);
                    delDirectional = r[0]; aiCascade += r[1];
                }
                if (linkedCutoff != null) {
                    long[] r = deleteCategory(Category.LINKED, linkedCutoff, batchSize, mode);
                    delLinked = r[0]; aiCascade += r[1];
                }
            } catch (Exception ex) {
                err = ex.getClass().getSimpleName() + ": " + ex.getMessage();
                log.error("[SignalCleanup] failed: {}", err, ex);
            }

            long finishedAt = System.currentTimeMillis();
            long duration = finishedAt - startedAt;

            // 落库最近一次运行统计
            try {
                configService.updateLastRun(delNeutral, delDirectional, delLinked, duration, err);
            } catch (Exception ex) {
                log.warn("[SignalCleanup] persist last-run stats failed: {}", ex.getMessage());
            }

            long totalDeleted = delNeutral + delDirectional + delLinked;
            log.info("[SignalCleanup] END trigger={}, mode={}, deleted total={} (neutral={}, directional={}, linked={}), rocksdb-ai={}, duration={}ms, err={}",
                    trigger, mode, totalDeleted, delNeutral, delDirectional, delLinked, aiCascade, duration, err);

            return SignalCleanupRunResultVO.builder()
                    .trigger(trigger)
                    .deleteMode(mode)
                    .deletedNeutral(delNeutral)
                    .deletedDirectionalOrphan(delDirectional)
                    .deletedLinked(delLinked)
                    .deletedTotal(totalDeleted)
                    .rocksdbAiAnalysisDeleted(aiCascade)
                    .startedAt(startedAt)
                    .finishedAt(finishedAt)
                    .durationMs(duration)
                    .errorMessage(err)
                    .build();
        } finally {
            runLock.unlock();
        }
    }

    // ─── 定时触发：每分钟 tick，看 cron 是否命中 ─────────────

    @Scheduled(fixedRate = 60_000L, initialDelay = 30_000L)
    public void scheduledTick() {
        SignalCleanupConfig cfg;
        try {
            cfg = configService.get();
        } catch (Exception e) {
            log.warn("[SignalCleanup] tick: read config failed: {}", e.getMessage());
            return;
        }
        if (cfg.getEnabled() == null || cfg.getEnabled() != 1) return;

        String cron = cfg.getScheduleCron();
        if (cron == null || cron.isBlank()) return;

        CronExpression expr;
        try {
            expr = CronExpression.parse(cron.trim());
        } catch (Exception ex) {
            log.warn("[SignalCleanup] invalid cron '{}': {}", cron, ex.getMessage());
            return;
        }

        LocalDateTime now = LocalDateTime.now(ZoneId.systemDefault());
        // 检查过去 1 分钟窗口内是否有 cron 触发点
        LocalDateTime windowStart = now.minusMinutes(1);
        LocalDateTime nextAfterWindow = expr.next(windowStart);
        if (nextAfterWindow == null || nextAfterWindow.isAfter(now)) return;

        // 避免同一分钟重复触发
        LocalDateTime last = lastCronFireAt.get();
        if (last != null && !nextAfterWindow.isAfter(last)) return;
        if (!lastCronFireAt.compareAndSet(last, nextAfterWindow)) return;

        log.info("[SignalCleanup] cron fired at {} (cron='{}')", nextAfterWindow, cron);
        runCleanup("SCHEDULED");
    }

    // ─── 内部：单类别循环删 ───────────────────────────────────

    private enum Category { NEUTRAL, DIRECTIONAL_ORPHAN, LINKED }

    /** @return long[]{deletedTotal, rocksdbAiCascade} */
    private long[] deleteCategory(Category cat, long cutoffMs, int batchSize, String mode) {
        long deleted = 0;
        long aiCascade = 0;
        for (int i = 0; i < MAX_BATCH_ITERATIONS; i++) {
            int rows;
            if ("HARD".equals(mode)) {
                List<Long> ids = fetchIds(cat, cutoffMs, batchSize);
                if (ids.isEmpty()) break;
                rows = cleanupMapper.hardDeleteByIds(ids);
                // RocksDB 级联：AI 分析（ai:rt:{signalId}）
                if (aiAnalysisStore != null) {
                    for (Long id : ids) {
                        try {
                            aiAnalysisStore.deleteSignalAnalysis(id);
                            aiCascade++;
                        } catch (Exception ex) {
                            // 单条失败忽略，log 已在 store 里打
                        }
                    }
                }
            } else {
                rows = softDeleteBatch(cat, cutoffMs, batchSize);
            }
            if (rows <= 0) break;
            deleted += rows;
            if (rows < batchSize) break;   // 不满一批，说明清完了
        }
        return new long[]{deleted, aiCascade};
    }

    private List<Long> fetchIds(Category cat, long cutoffMs, int limit) {
        return switch (cat) {
            case NEUTRAL             -> cleanupMapper.selectNeutralIdsOlderThan(cutoffMs, limit);
            case DIRECTIONAL_ORPHAN  -> cleanupMapper.selectDirectionalOrphanIdsOlderThan(cutoffMs, limit);
            case LINKED              -> cleanupMapper.selectLinkedIdsOlderThan(cutoffMs, limit);
        };
    }

    private int softDeleteBatch(Category cat, long cutoffMs, int limit) {
        return switch (cat) {
            case NEUTRAL             -> cleanupMapper.softDeleteNeutralBatch(cutoffMs, limit);
            case DIRECTIONAL_ORPHAN  -> cleanupMapper.softDeleteDirectionalOrphanBatch(cutoffMs, limit);
            case LINKED              -> cleanupMapper.softDeleteLinkedBatch(cutoffMs, limit);
        };
    }

    // ─── 辅助 ─────────────────────────────────────────────────

    /**
     * 计算某类的截止时间戳；NULL / <=0 表示不清理该类；
     * 再和 protectCutoff 取 min（即使配置 keepDays=1 但 protect=3，也用 3 天）。
     */
    private static Long cutoffFor(Integer keepDays, long now, long protectCutoff) {
        if (keepDays == null || keepDays <= 0) return null;
        long tentative = now - (long) keepDays * DAY_MS;
        return Math.min(tentative, protectCutoff);
    }

    private static int safeDays(Integer v, int fallback) {
        return (v == null || v < 0) ? fallback : v;
    }

    private static int safeBatch(Integer v) {
        if (v == null || v <= 0) return 1000;
        if (v > 10_000) return 10_000;
        return v;
    }

    private static SignalCleanupRunResultVO skipped(String trigger, String mode, long startedAt, String reason) {
        long now = System.currentTimeMillis();
        return SignalCleanupRunResultVO.builder()
                .trigger(trigger)
                .deleteMode(mode)
                .deletedNeutral(0).deletedDirectionalOrphan(0).deletedLinked(0).deletedTotal(0)
                .rocksdbAiAnalysisDeleted(0)
                .startedAt(startedAt).finishedAt(now).durationMs(now - startedAt)
                .errorMessage(reason)
                .build();
    }
}

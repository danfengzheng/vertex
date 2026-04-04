package com.vertex.service.order.task;

import com.vertex.service.order.cache.ExchangeSymbolCache;
import com.vertex.service.order.sync.BinanceSymbolSyncService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 币对同步定时任务
 * <p>
 * 启动时执行一次全量同步（异步，不阻塞启动流程）。
 * 之后每天凌晨 2 点自动同步一次（可通过配置覆盖）。
 * 支持通过 HTTP 接口手动触发（{@link com.vertex.admin.web.controller.ExchangeSymbolController}）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SymbolSyncTask {

    private final BinanceSymbolSyncService binanceSyncService;
    private final ExchangeSymbolCache cache;

    /** 幂等锁：同一时刻只允许一个同步任务在跑（手动触发 + 定时触发互斥） */
    private final AtomicBoolean syncing = new AtomicBoolean(false);

    /**
     * 启动后延迟 10 秒执行首次同步，避免与其他初始化任务竞争 DB 连接。
     * 使用独立线程，不阻塞 Spring 上下文启动。
     */
    @PostConstruct
    public void syncOnStartup() {
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(10_000);
                log.info("[SymbolSync] Startup sync starting...");
                doSync();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.warn("[SymbolSync] Startup sync failed: {}", e.getMessage(), e);
            }
        }, "symbol-sync-startup");
        t.setDaemon(true);
        t.start();
    }

    /**
     * 每天凌晨 2:00 全量同步（cron 可通过配置覆盖）。
     */
    @Scheduled(cron = "${vertex.trading.symbol-sync-cron:0 0 2 * * ?}")
    public void syncDaily() {
        log.info("[SymbolSync] Daily sync starting...");
        doSync();
    }

    /**
     * 手动触发入口（供 Controller 调用）。
     */
    public int syncManual() {
        log.info("[SymbolSync] Manual sync triggered");
        return doSync();
    }

    // ─── 私有 ─────────────────────────────────────────────────────────────

    private int doSync() {
        if (!syncing.compareAndSet(false, true)) {
            log.warn("[SymbolSync] Already in progress, skipping this trigger");
            return 0;
        }
        try {
            int count = binanceSyncService.syncAll();
            cache.refresh();
            log.info("[SymbolSync] Done, {} records upserted", count);
            return count;
        } catch (Exception e) {
            log.error("[SymbolSync] Sync failed: {}", e.getMessage(), e);
            throw e;
        } finally {
            syncing.set(false);
        }
    }
}

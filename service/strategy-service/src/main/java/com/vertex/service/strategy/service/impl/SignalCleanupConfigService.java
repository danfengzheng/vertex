package com.vertex.service.strategy.service.impl;

import com.vertex.model.entity.strategy.SignalCleanupConfig;
import com.vertex.service.strategy.mapper.SignalCleanupConfigMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 信号清理动态配置服务。
 * <p>
 * 从 {@code signal_cleanup_config} 单行表读写；5s 内存缓存降低 DB 压力。
 * 首次部署未跑 V26 时返回内置默认（enabled=0）优雅降级。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SignalCleanupConfigService {

    private static final long CACHE_TTL_MS = 5_000L;
    private static final Long CONFIG_ID = 1L;

    private final SignalCleanupConfigMapper mapper;
    private final AtomicReference<CacheEntry> cache = new AtomicReference<>(null);

    @PostConstruct
    public void init() {
        SignalCleanupConfig cfg = loadFromDb();
        log.info("[SignalCleanupCfg] initialized: enabled={}, neutral={}d, directional={}d, linked={}d, "
                        + "protect={}d, cron='{}', mode={}",
                cfg.getEnabled() == 1, cfg.getKeepNeutralDays(), cfg.getKeepDirectionalDays(),
                cfg.getKeepLinkedDays(), cfg.getProtectRecentDays(), cfg.getScheduleCron(), cfg.getDeleteMode());
    }

    /** 拿当前配置（带 5s 缓存） */
    public SignalCleanupConfig get() {
        CacheEntry e = cache.get();
        long now = System.currentTimeMillis();
        if (e != null && (now - e.loadedAt) < CACHE_TTL_MS) {
            return e.config;
        }
        SignalCleanupConfig fresh = loadFromDb();
        cache.set(new CacheEntry(fresh, now));
        return fresh;
    }

    /** 保存并立即失效缓存 */
    public void save(SignalCleanupConfig cfg, Long updateBy) {
        if (cfg.getId() == null) cfg.setId(CONFIG_ID);
        cfg.setUpdateBy(updateBy);
        int updated = mapper.updateById(cfg);
        if (updated == 0) {
            mapper.insert(cfg);
        }
        invalidate();
        log.info("[SignalCleanupCfg] saved: enabled={}, cron='{}', mode={}, updateBy={}",
                cfg.getEnabled() == 1, cfg.getScheduleCron(), cfg.getDeleteMode(), updateBy);
    }

    /**
     * 只写入最近一次运行统计字段（不冲刷用户配置）。
     */
    public void updateLastRun(long deletedNeutral, long deletedDirectional, long deletedLinked,
                              long durationMs, String errorMessage) {
        SignalCleanupConfig cur = mapper.selectById(CONFIG_ID);
        if (cur == null) return;
        cur.setLastRunAt(java.time.LocalDateTime.now());
        cur.setLastRunDeletedNeutral(deletedNeutral);
        cur.setLastRunDeletedDirectional(deletedDirectional);
        cur.setLastRunDeletedLinked(deletedLinked);
        cur.setLastRunDurationMs(durationMs);
        cur.setLastRunError(errorMessage);
        mapper.updateById(cur);
        invalidate();
    }

    public void invalidate() {
        cache.set(null);
    }

    private SignalCleanupConfig loadFromDb() {
        try {
            SignalCleanupConfig row = mapper.selectById(CONFIG_ID);
            if (row != null) return row;
        } catch (Exception ex) {
            log.warn("[SignalCleanupCfg] load DB failed, using defaults: {}", ex.getMessage());
        }
        log.warn("[SignalCleanupCfg] no config row (id=1); run V26. Falling back to defaults (disabled).");
        return defaults();
    }

    private static SignalCleanupConfig defaults() {
        return SignalCleanupConfig.builder()
                .id(CONFIG_ID)
                .enabled(0)
                .keepNeutralDays(7)
                .keepDirectionalDays(30)
                .keepLinkedDays(365)
                .protectRecentDays(3)
                .scheduleCron("0 0 3 * * ?")
                .deleteMode("SOFT")
                .batchSize(1000)
                .lastRunDeletedNeutral(0L)
                .lastRunDeletedDirectional(0L)
                .lastRunDeletedLinked(0L)
                .lastRunDurationMs(0L)
                .build();
    }

    private record CacheEntry(SignalCleanupConfig config, long loadedAt) {}
}

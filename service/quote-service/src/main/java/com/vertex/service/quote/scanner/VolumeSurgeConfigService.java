package com.vertex.service.quote.scanner;

import com.vertex.model.entity.quote.VolumeSurgeConfig;
import com.vertex.service.quote.mapper.VolumeSurgeConfigMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 币安现货成交量暴增扫描器动态配置服务。
 * <p>
 * 单行 config 表（id=1）读写。为避免扫描器每个候选都打 DB，做 5s 内存缓存，
 * 保存后立即失效缓存让下一次扫描或 UI 刷新拿到新值。
 * </p>
 * <p>
 * 如果 DB 里没有配置行（首次部署未跑 V20 迁移），返回一份内置默认值，
 * 让 scanner 优雅降级为 disabled 状态；同时打 warn 日志提醒。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VolumeSurgeConfigService {

    private static final long CACHE_TTL_MS = 5_000L;
    private static final Long CONFIG_ID = 1L;

    private final VolumeSurgeConfigMapper mapper;

    /** 缓存的 config + 加载时刻 */
    private final AtomicReference<CacheEntry> cache = new AtomicReference<>(null);

    @PostConstruct
    public void init() {
        // 启动期尝试预热一次，让日志暴露"没有配置行"的问题
        VolumeSurgeConfig cfg = loadFromDb();
        log.info("[VolumeSurgeConfigService] initialized: enabled={}, threshold={}x, interval={}min, tgEnabled={}",
                yes(cfg.getEnabled()), cfg.getSurgeRatioThreshold(),
                cfg.getScanIntervalMinutes(), yes(cfg.getTelegramEnabled()));
    }

    /** 拿当前配置（带 5s 缓存） */
    public VolumeSurgeConfig get() {
        CacheEntry e = cache.get();
        long now = System.currentTimeMillis();
        if (e != null && (now - e.loadedAt) < CACHE_TTL_MS) {
            return e.config;
        }
        VolumeSurgeConfig fresh = loadFromDb();
        cache.set(new CacheEntry(fresh, now));
        return fresh;
    }

    /** 保存配置：更新 DB + 失效缓存 */
    public void save(VolumeSurgeConfig cfg, Long updateBy) {
        if (cfg.getId() == null) cfg.setId(CONFIG_ID);
        cfg.setUpdateBy(updateBy);
        int updated = mapper.updateById(cfg);
        if (updated == 0) {
            // 表里没这一行 → 插入
            mapper.insert(cfg);
        }
        invalidate();
        log.info("[VolumeSurgeConfigService] config saved: enabled={}, threshold={}x, interval={}min, updateBy={}",
                yes(cfg.getEnabled()), cfg.getSurgeRatioThreshold(),
                cfg.getScanIntervalMinutes(), updateBy);
    }

    /** 显式失效缓存（保存后自动调用；也可对外用于 hot reload） */
    public void invalidate() {
        cache.set(null);
    }

    // ─── 内部 ─────────────────────────────────────────────────

    private VolumeSurgeConfig loadFromDb() {
        try {
            VolumeSurgeConfig row = mapper.selectById(CONFIG_ID);
            if (row != null) return row;
        } catch (Exception ex) {
            log.warn("[VolumeSurgeConfigService] load DB failed, using defaults: {}", ex.getMessage());
        }
        log.warn("[VolumeSurgeConfigService] no config row (id=1); run V20_volume_surge_config.sql. " +
                "Falling back to internal defaults (disabled).");
        return defaults();
    }

    private static VolumeSurgeConfig defaults() {
        return VolumeSurgeConfig.builder()
                .id(CONFIG_ID)
                .enabled(0)
                .scanIntervalMinutes(15)
                .quoteCurrency("USDT")
                .surgeRatioThreshold(new BigDecimal("10.00"))
                .minPriceChange1hPct(new BigDecimal("2.00"))
                .baselineHours(24)
                .minBaselineMedianUsdt(new BigDecimal("5000"))
                .min24hQuoteVolumeUsdt(new BigDecimal("50000"))
                .max24hQuoteVolumeUsdt(new BigDecimal("10000000"))
                .prefilterMinAbs24hPriceChangePct(new BigDecimal("3.00"))
                .excludeDaysSinceListing(7)
                .cooldownHours(6)
                .alertDirections("BOTH")
                .includeUnclosedBar(1)
                .symbolBlacklist("USDCUSDT,FDUSDT,TUSDUSDT,DAIUSDT")
                .telegramEnabled(0)
                .build();
    }

    private static boolean yes(Integer v) {
        return v != null && v == 1;
    }

    private record CacheEntry(VolumeSurgeConfig config, long loadedAt) {}
}

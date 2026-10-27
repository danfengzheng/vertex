package com.vertex.service.farb.signal;

import com.vertex.service.farb.config.FarbProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 每日凌晨清理 RocksDB 里过期信号。TTL 由 {@code vertex.farb.signal-ttl-days} 控制。
 * 0 = 不清理（永久保留，仅测试用）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FarbSignalPurgeJob {

    private final FarbProperties properties;
    private final FarbSignalStore signalStore;

    /** 每日 03:00 执行 */
    @Scheduled(cron = "0 0 3 * * *")
    public void purge() {
        if (properties.getSignalTtlDays() <= 0) return;
        long cutoff = System.currentTimeMillis()
                - TimeUnit.DAYS.toMillis(properties.getSignalTtlDays());
        int total = 0;
        total += signalStore.purgeExpired(FarbSignalStore.PFX_PREM,    cutoff);
        total += signalStore.purgeExpired(FarbSignalStore.PFX_OI,      cutoff);
        total += signalStore.purgeExpired(FarbSignalStore.PFX_LSR,     cutoff);
        total += signalStore.purgeExpired(FarbSignalStore.PFX_TBSR,    cutoff);
        total += signalStore.purgeExpired(FarbSignalStore.PFX_VOL,     cutoff);
        // FUNDING 历史不清（数据量小）
        log.info("[FarbSignalPurge] cleaned {} entries older than {}d", total, properties.getSignalTtlDays());
    }
}

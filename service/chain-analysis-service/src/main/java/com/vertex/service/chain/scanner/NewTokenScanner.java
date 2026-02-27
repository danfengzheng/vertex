package com.vertex.service.chain.scanner;

import com.vertex.service.chain.config.ChainAnalysisProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Random;

/**
 * 新代币定时扫描器
 * <p>
 * 通过 {@code @Scheduled(fixedDelay)} + 随机抖动，实现 5~15 分钟不等间隔的扫描，
 * 避免所有节点在同一时刻请求外部 API（在多实例部署场景下也能分散压力）。
 *
 * <p>实际扫描间隔 = {@code scanIntervalSeconds} + random(0, {@code maxJitterSeconds})，
 * 默认配置下为 300s ~ 900s（5~15 分钟）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NewTokenScanner {

    private final ChainAnalysisProperties properties;
    private final ChainTokenScanService scanService;

    private static final Random RANDOM = new Random();

    /**
     * 固定延迟触发（上次执行完成后等待至少 {@code scan-interval-seconds} 秒）。
     * 属性值来自 {@code vertex.chain.scan-interval-seconds}，默认 300 秒（5 分钟）。
     * 实际触发间隔 = scanIntervalSeconds + jitter(0 ~ maxJitterSeconds)。
     */
    @Scheduled(fixedDelayString = "${vertex.chain.scan-interval-seconds:300}000")
    public void scan() {
        // 随机抖动：避免固定时间点的规律性请求
        int jitterMs = RANDOM.nextInt(properties.getMaxJitterSeconds() * 1000 + 1);
        if (jitterMs > 0) {
            try {
                Thread.sleep(jitterMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }

        log.info("[NewTokenScanner] Triggered (jitter={}ms)", jitterMs);
        try {
            scanService.scanAll(maxScanWindow());
        } catch (Exception e) {
            log.error("[NewTokenScanner] Scan failed: {}", e.getMessage(), e);
        }
    }

    /**
     * 取各链中最大的扫描窗口，确保不遗漏代币
     */
    private int maxScanWindow() {
        return Math.max(
                properties.getBnb().getScanWindowMinutes(),
                properties.getSolana().getScanWindowMinutes()
        );
    }
}

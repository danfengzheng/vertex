package com.vertex.service.strategy.controller;

import com.vertex.common.core.annotation.RequiresPermission;
import com.vertex.model.entity.strategy.SignalCleanupConfig;
import com.vertex.model.vo.strategy.SignalCleanupPreviewVO;
import com.vertex.model.vo.strategy.SignalCleanupRunResultVO;
import com.vertex.service.strategy.service.impl.SignalCleanupConfigService;
import com.vertex.service.strategy.service.impl.SignalCleanupService;
import com.vertex.web.response.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.web.bind.annotation.*;

/**
 * 信号清理控制器。
 * <p>
 * 权限 {@code signal:cleanup} 由 V26 迁移写进 sys_menu；管理员角色默认拥有。
 * </p>
 */
@Tag(name = "信号清理")
@RestController
@RequestMapping("/admin/signal/cleanup")
@RequiredArgsConstructor
public class SignalCleanupController {

    private final SignalCleanupConfigService configService;
    private final SignalCleanupService cleanupService;

    @RequiresPermission("signal:cleanup")
    @GetMapping("/config")
    @Operation(summary = "读取信号清理配置")
    public Result<SignalCleanupConfig> getConfig() {
        return Result.success(configService.get());
    }

    @RequiresPermission("signal:cleanup")
    @PutMapping("/config")
    @Operation(summary = "更新信号清理配置（5s 内生效）")
    public Result<SignalCleanupConfig> updateConfig(@RequestBody SignalCleanupConfig cfg) {
        cfg.setId(1L);
        clamp(cfg);
        configService.save(cfg, null);
        return Result.success(configService.get());
    }

    @RequiresPermission("signal:cleanup")
    @GetMapping("/preview")
    @Operation(summary = "预览：本次将删除多少条")
    public Result<SignalCleanupPreviewVO> preview() {
        return Result.success(cleanupService.preview());
    }

    @RequiresPermission("signal:cleanup")
    @PostMapping("/run")
    @Operation(summary = "立即执行清理（手动触发，无视 enabled 开关）")
    public Result<SignalCleanupRunResultVO> run() {
        return Result.success(cleanupService.runCleanup("MANUAL"));
    }

    // ─── 校验 / 兜底 ───────────────────────────────────────────

    private void clamp(SignalCleanupConfig cfg) {
        if (cfg.getEnabled() == null || (cfg.getEnabled() != 0 && cfg.getEnabled() != 1)) {
            cfg.setEnabled(0);
        }
        cfg.setKeepNeutralDays(clampNullableDays(cfg.getKeepNeutralDays()));
        cfg.setKeepDirectionalDays(clampNullableDays(cfg.getKeepDirectionalDays()));
        cfg.setKeepLinkedDays(clampNullableDays(cfg.getKeepLinkedDays()));
        cfg.setProtectRecentDays(clampInt(cfg.getProtectRecentDays(), 0, 365, 3));
        cfg.setBatchSize(clampInt(cfg.getBatchSize(), 100, 10_000, 1000));

        String mode = cfg.getDeleteMode();
        if (mode == null || (!"SOFT".equalsIgnoreCase(mode) && !"HARD".equalsIgnoreCase(mode))) {
            cfg.setDeleteMode("SOFT");
        } else {
            cfg.setDeleteMode(mode.toUpperCase());
        }

        // cron 无效直接兜底为默认值（避免定时任务在下一 tick 崩）
        String cron = cfg.getScheduleCron();
        if (cron == null || cron.isBlank()) {
            cfg.setScheduleCron("0 0 3 * * ?");
        } else {
            try {
                CronExpression.parse(cron.trim());
                cfg.setScheduleCron(cron.trim());
            } catch (Exception ex) {
                cfg.setScheduleCron("0 0 3 * * ?");
            }
        }
    }

    /** null / -1 都表示"不清理该类"，直接保留 null（写库为 NULL）；正数 clamp 到 [1, 3650] */
    private static Integer clampNullableDays(Integer v) {
        if (v == null || v <= 0) return null;
        if (v > 3650) return 3650;
        return v;
    }

    private static Integer clampInt(Integer v, int min, int max, int fallback) {
        if (v == null) return fallback;
        if (v < min) return min;
        if (v > max) return max;
        return v;
    }
}

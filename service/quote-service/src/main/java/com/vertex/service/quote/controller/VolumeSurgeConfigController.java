package com.vertex.service.quote.controller;

import com.vertex.common.core.annotation.RequiresPermission;
import com.vertex.model.entity.quote.VolumeSurgeConfig;
import com.vertex.service.quote.scanner.VolumeSurgeConfigService;
import com.vertex.service.quote.scanner.VolumeSurgeScanner;
import com.vertex.web.response.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * 币安现货成交量暴增扫描器动态配置控制器。
 * <p>
 * 权限 {@code quote:volume-surge} 由 V20 迁移写进 sys_menu；管理员角色默认拥有。
 * </p>
 * <p>
 * 只在 yaml {@code vertex.quote.volume-surge.enabled=true} 时才有 config service bean，
 * 因此 controller 也用 @Autowired(required=false) 软依赖：yaml 关掉时接口返回 400 而非崩溃。
 * </p>
 */
@Tag(name = "行情异动扫描器")
@RestController
@RequestMapping("/admin/quote/volume-surge")
public class VolumeSurgeConfigController {

    @Autowired(required = false)
    private VolumeSurgeConfigService configService;

    @Autowired(required = false)
    private VolumeSurgeScanner scanner;

    @RequiresPermission("quote:volume-surge")
    @GetMapping("/config")
    @Operation(summary = "读取扫描器动态配置")
    public Result<VolumeSurgeConfig> getConfig() {
        if (configService == null) {
            return Result.fail(400, "Volume-surge scanner not installed (vertex.quote.volume-surge.enabled=false)");
        }
        return Result.success(configService.get());
    }

    @RequiresPermission("quote:volume-surge")
    @PutMapping("/config")
    @Operation(summary = "更新扫描器动态配置（立即生效，无需重启）")
    public Result<VolumeSurgeConfig> updateConfig(@RequestBody VolumeSurgeConfig cfg) {
        if (configService == null) {
            return Result.fail(400, "Volume-surge scanner not installed");
        }
        // 强制单行主键
        cfg.setId(1L);
        // 校验：clamp 一些容易搞错的数值
        clamp(cfg);
        configService.save(cfg, null);
        return Result.success(configService.get());
    }

    @RequiresPermission("quote:volume-surge")
    @GetMapping("/status")
    @Operation(summary = "扫描器运行时状态：上次扫描时间 / 上次告警数 / 权重占用")
    public Result<StatusVO> status() {
        StatusVO vo = new StatusVO();
        vo.installed = configService != null;
        if (scanner != null) {
            vo.lastScanAt = scanner.getLastScanAt();
            vo.lastAlertCount = scanner.getLastAlertCount();
        }
        return Result.success(vo);
    }

    // ─── 内部工具 ─────────────────────────────────────────────

    private void clamp(VolumeSurgeConfig cfg) {
        // 一些明显不合理的极端值直接兜底
        if (cfg.getScanIntervalMinutes() != null && cfg.getScanIntervalMinutes() < 1) {
            cfg.setScanIntervalMinutes(1);
        }
        if (cfg.getBaselineHours() != null && cfg.getBaselineHours() < 6) {
            cfg.setBaselineHours(6);
        }
        if (cfg.getCooldownHours() != null && cfg.getCooldownHours() < 0) {
            cfg.setCooldownHours(0);
        }
        if (cfg.getExcludeDaysSinceListing() != null && cfg.getExcludeDaysSinceListing() < 0) {
            cfg.setExcludeDaysSinceListing(0);
        }
        // 方向枚举 uppercase
        if (cfg.getAlertDirections() != null) {
            String d = cfg.getAlertDirections().trim().toUpperCase();
            if (!d.equals("UP") && !d.equals("DOWN") && !d.equals("BOTH")) d = "BOTH";
            cfg.setAlertDirections(d);
        }
    }

    /** 简单运行时状态 VO */
    public static class StatusVO {
        public boolean installed;
        public long lastScanAt;
        public int lastAlertCount;
    }
}

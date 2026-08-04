package com.vertex.service.strategy.controller;

import com.vertex.common.core.annotation.RequiresPermission;
import com.vertex.model.entity.ai.AiConfig;
import com.vertex.service.strategy.ai.AiConfigService;
import com.vertex.web.response.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * AI 模块动态配置控制器。
 * <p>
 * yaml 里 {@code vertex.ai.enabled=true} 才会激活 AI beans（含 AiConfigService）；
 * 未启用时接口返回 400 而非崩溃。
 * </p>
 * <p>
 * 权限 {@code ai:config} 由 V22 迁移写进 sys_menu；管理员角色默认拥有。
 * </p>
 */
@Tag(name = "AI 配置")
@RestController
@RequestMapping("/admin/ai/config")
public class AiConfigController {

    @Autowired(required = false)
    private AiConfigService configService;

    @RequiresPermission("ai:config")
    @GetMapping
    @Operation(summary = "读取 AI 模块动态配置")
    public Result<AiConfig> getConfig() {
        if (configService == null) {
            return Result.fail(400, "AI module not installed (vertex.ai.enabled=false)");
        }
        return Result.success(configService.get());
    }

    @RequiresPermission("ai:config")
    @PutMapping
    @Operation(summary = "更新 AI 模块动态配置（立即生效，无需重启）")
    public Result<AiConfig> updateConfig(@RequestBody AiConfig cfg) {
        if (configService == null) {
            return Result.fail(400, "AI module not installed");
        }
        cfg.setId(1L);
        clamp(cfg);
        configService.save(cfg, null);
        return Result.success(configService.get());
    }

    private void clamp(AiConfig cfg) {
        // provider 规范化 + 兜底
        if (cfg.getProvider() != null) {
            String p = cfg.getProvider().trim().toLowerCase();
            if (!"gemini".equals(p) && !"deepseek".equals(p)) p = "gemini";
            cfg.setProvider(p);
        } else {
            cfg.setProvider("gemini");
        }
        if (cfg.getLanguage() == null || cfg.getLanguage().isBlank()) {
            cfg.setLanguage("zh-CN");
        }
        // timeout / retry 极端值兜底
        cfg.setGeminiTimeoutSeconds(clampInt(cfg.getGeminiTimeoutSeconds(), 5, 300, 30));
        cfg.setGeminiMaxRetry(clampInt(cfg.getGeminiMaxRetry(), 0, 10, 2));
        cfg.setDeepseekTimeoutSeconds(clampInt(cfg.getDeepseekTimeoutSeconds(), 5, 300, 60));
        cfg.setDeepseekMaxRetry(clampInt(cfg.getDeepseekMaxRetry(), 0, 10, 2));
        // reasoning_effort 只允许 low/medium/high；其他值置 null
        if (cfg.getDeepseekReasoningEffort() != null) {
            String e = cfg.getDeepseekReasoningEffort().trim().toLowerCase();
            if (e.isEmpty()) {
                cfg.setDeepseekReasoningEffort(null);
            } else if (!e.equals("low") && !e.equals("medium") && !e.equals("high")) {
                cfg.setDeepseekReasoningEffort(null);
            } else {
                cfg.setDeepseekReasoningEffort(e);
            }
        }
        // thinking_enabled 只允许 0/1/null
        Integer te = cfg.getDeepseekThinkingEnabled();
        if (te != null && te != 0 && te != 1) {
            cfg.setDeepseekThinkingEnabled(0);
        }
    }

    private static Integer clampInt(Integer v, int min, int max, int fallback) {
        if (v == null) return fallback;
        if (v < min) return min;
        if (v > max) return max;
        return v;
    }
}

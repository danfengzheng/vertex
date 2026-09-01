package com.vertex.service.strategy.controller;

import com.vertex.common.core.annotation.RequiresPermission;
import com.vertex.model.dto.strategy.TemplateBuildDTO;
import com.vertex.model.dto.strategy.TemplateSaveDTO;
import com.vertex.model.entity.strategy.Strategy;
import com.vertex.model.entity.strategy.StrategyTemplateHistory;
import com.vertex.model.vo.strategy.BacktestResultVO;
import com.vertex.model.vo.strategy.StrategyTemplateVO;
import com.vertex.model.vo.strategy.TemplateBacktestCompareVO;
import com.vertex.service.strategy.template.StrategyTemplateService;
import com.vertex.service.strategy.template.TemplateValidator;
import com.vertex.web.response.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 策略模板 REST 接口。
 * <p>
 * 权限 {@code strategy:template} 由 V27 迁移写进 sys_menu；管理员默认拥有。
 * </p>
 */
@Tag(name = "策略模板")
@RestController
@RequestMapping("/admin/strategy-template")
@RequiredArgsConstructor
public class StrategyTemplateController {

    private final StrategyTemplateService service;
    private final TemplateValidator validator;

    // ─── CRUD ────────────────────────────────────────────────

    @RequiresPermission("strategy:template")
    @GetMapping
    @Operation(summary = "模板列表（可按 status/category 过滤）")
    public Result<List<StrategyTemplateVO>> list(@RequestParam(required = false) String status,
                                                 @RequestParam(required = false) String category) {
        return Result.success(service.list(status, category));
    }

    @RequiresPermission("strategy:template")
    @GetMapping("/{id}")
    @Operation(summary = "模板详情")
    public Result<StrategyTemplateVO> get(@PathVariable Long id) {
        StrategyTemplateVO vo = service.get(id);
        return vo == null ? Result.fail(404, "template not found") : Result.success(vo);
    }

    @RequiresPermission("strategy:template")
    @GetMapping("/by-code/{code}")
    @Operation(summary = "按 code 查模板")
    public Result<StrategyTemplateVO> getByCode(@PathVariable String code) {
        StrategyTemplateVO vo = service.getByCode(code);
        return vo == null ? Result.fail(404, "template not found") : Result.success(vo);
    }

    @RequiresPermission("strategy:template")
    @PostMapping
    @Operation(summary = "新增/更新模板（publish=true 时同时发布并写快照）")
    public Result<StrategyTemplateVO> save(@RequestBody TemplateSaveDTO dto) {
        return Result.success(service.save(dto, null));
    }

    @RequiresPermission("strategy:template")
    @DeleteMapping("/{id}")
    @Operation(summary = "删除模板（内置模板拒绝）")
    public Result<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return Result.success();
    }

    @RequiresPermission("strategy:template")
    @GetMapping("/{id}/history")
    @Operation(summary = "模板发布历史")
    public Result<List<StrategyTemplateHistory>> history(@PathVariable Long id) {
        return Result.success(service.history(id));
    }

    // ─── 校验 ────────────────────────────────────────────────

    @RequiresPermission("strategy:template")
    @PostMapping("/validate")
    @Operation(summary = "校验 template + exposedParams 结构")
    public Result<TemplateValidator.ValidationResult> validate(@RequestBody TemplateSaveDTO dto) {
        var exposed = dto.getExposedParams() == null ? java.util.List.<com.vertex.model.vo.strategy.TemplateExposedParam>of()
                : com.alibaba.fastjson2.JSON.parseArray(
                        com.alibaba.fastjson2.JSON.toJSONString(dto.getExposedParams()),
                        com.vertex.model.vo.strategy.TemplateExposedParam.class);
        return Result.success(validator.validate(dto.getTemplate(), exposed));
    }

    // ─── build / preview / quickBacktest ─────────────────────

    @RequiresPermission("strategy:template")
    @PostMapping("/preview")
    @Operation(summary = "预览：只生成 Strategy 结构（不落库）")
    public Result<Strategy> preview(@RequestBody TemplateBuildDTO dto) {
        return Result.success(service.preview(dto));
    }

    @RequiresPermission("strategy:template")
    @PostMapping("/build")
    @Operation(summary = "从模板落库生成 Strategy")
    public Result<Strategy> build(@RequestBody TemplateBuildDTO dto) {
        return Result.success(service.build(dto));
    }

    @RequiresPermission("strategy:template")
    @PostMapping("/quick-backtest")
    @Operation(summary = "预览 + 临时策略跑一次回测（不留痕）")
    public Result<BacktestResultVO> quickBacktest(@RequestBody TemplateBuildDTO dto) {
        return Result.success(service.quickBacktest(dto));
    }

    // ─── A/B 对比 ────────────────────────────────────────────

    @RequiresPermission("strategy:template")
    @PostMapping("/ab-compare")
    @Operation(summary = "A/B 对比：N 个候选（模板+参数）同区间回测汇总")
    public Result<TemplateBacktestCompareVO> abCompare(@RequestBody AbCompareRequest req) {
        return Result.success(service.abCompare(
                req.getCandidates(), req.getStartTime(), req.getEndTime(), req.getInitialCapital()));
    }

    @lombok.Data
    public static class AbCompareRequest {
        private List<TemplateBuildDTO> candidates;
        private Long startTime;
        private Long endTime;
        private BigDecimal initialCapital;
    }
}

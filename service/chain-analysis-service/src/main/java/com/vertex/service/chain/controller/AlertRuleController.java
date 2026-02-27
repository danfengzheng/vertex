package com.vertex.service.chain.controller;

import com.vertex.api.chain.IAlertRuleService;
import com.vertex.common.core.page.PageResult;
import com.vertex.model.dto.chain.AlertRuleCreateDTO;
import com.vertex.model.dto.chain.AlertRuleUpdateDTO;
import com.vertex.model.query.PageQuery;
import com.vertex.model.vo.chain.AlertRuleVO;
import com.vertex.web.response.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * 链上告警规则 CRUD 接口
 */
@Tag(name = "链上告警规则")
@Validated
@RestController
@RequestMapping("/admin/chain/alert-rule")
@RequiredArgsConstructor
public class AlertRuleController {

    private final IAlertRuleService alertRuleService;

    @Operation(summary = "分页查询告警规则")
    @GetMapping("/page")
    public Result<PageResult<AlertRuleVO>> page(@Validated PageQuery query) {
        return Result.success(alertRuleService.page(query));
    }

    @Operation(summary = "查询告警规则详情")
    @GetMapping("/{id}")
    public Result<AlertRuleVO> getById(@PathVariable Long id) {
        return Result.success(alertRuleService.getById(id));
    }

    @Operation(summary = "创建告警规则")
    @PostMapping
    public Result<Long> create(@Validated @RequestBody AlertRuleCreateDTO dto) {
        return Result.success(alertRuleService.create(dto));
    }

    @Operation(summary = "更新告警规则")
    @PutMapping
    public Result<Void> update(@Validated @RequestBody AlertRuleUpdateDTO dto) {
        alertRuleService.update(dto);
        return Result.success();
    }

    @Operation(summary = "删除告警规则")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        alertRuleService.delete(id);
        return Result.success();
    }

    @Operation(summary = "启用告警规则")
    @PostMapping("/{id}/enable")
    public Result<Void> enable(@PathVariable Long id) {
        alertRuleService.enable(id);
        return Result.success();
    }

    @Operation(summary = "禁用告警规则")
    @PostMapping("/{id}/disable")
    public Result<Void> disable(@PathVariable Long id) {
        alertRuleService.disable(id);
        return Result.success();
    }
}

package com.vertex.service.chain.controller;

import com.vertex.api.chain.IChainSourceConfigService;
import com.vertex.model.dto.chain.ChainSourceConfigUpdateDTO;
import com.vertex.model.vo.chain.ChainSourceConfigVO;
import com.vertex.web.response.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import com.vertex.common.core.annotation.RequiresPermission;

import java.util.List;

/**
 * 链上数据源配置接口
 */
@Tag(name = "链上数据源配置")
@Validated
@RestController
@RequestMapping("/admin/chain/source-config")
@RequiredArgsConstructor
public class ChainSourceConfigController {

    private final IChainSourceConfigService sourceConfigService;

    @RequiresPermission("chain:alert")
    @Operation(summary = "查询所有数据源配置")
    @GetMapping
    public Result<List<ChainSourceConfigVO>> listAll() {
        return Result.success(sourceConfigService.listAll());
    }

    @RequiresPermission("chain:alert")
    @Operation(summary = "更新数据源配置")
    @PutMapping
    public Result<Void> update(@Validated @RequestBody ChainSourceConfigUpdateDTO dto) {
        sourceConfigService.update(dto);
        return Result.success();
    }
}

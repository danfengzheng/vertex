package com.vertex.service.chain.controller;

import com.vertex.api.chain.IChainTokenService;
import com.vertex.common.core.page.PageResult;
import com.vertex.model.dto.chain.ChainTokenQueryDTO;
import com.vertex.model.vo.chain.ChainTokenDetailVO;
import com.vertex.model.vo.chain.ChainTokenVO;
import com.vertex.web.response.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * 链上新币列表接口
 */
@Tag(name = "链上新币分析")
@Validated
@RestController
@RequestMapping("/admin/chain/token")
@RequiredArgsConstructor
public class ChainTokenController {

    private final IChainTokenService chainTokenService;

    @Operation(summary = "分页查询新币列表")
    @GetMapping("/page")
    public Result<PageResult<ChainTokenVO>> page(@Validated ChainTokenQueryDTO query) {
        return Result.success(chainTokenService.page(query));
    }

    @Operation(summary = "查询新币详情（含最新指标快照）")
    @GetMapping("/{id}")
    public Result<ChainTokenDetailVO> getById(@PathVariable Long id) {
        return Result.success(chainTokenService.getById(id));
    }

    @Operation(summary = "手动触发扫描（BNB / SOLANA / ALL）")
    @PostMapping("/scan")
    public Result<Void> scan(@RequestParam(defaultValue = "ALL") String chain) {
        chainTokenService.triggerScan(chain);
        return Result.success();
    }
}

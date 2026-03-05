package com.vertex.service.order.controller;

import com.vertex.api.trading.IExchangeAccountService;
import com.vertex.model.dto.trading.ExchangeAccountCreateDTO;
import com.vertex.model.dto.trading.ExchangeAccountUpdateDTO;
import com.vertex.model.vo.trading.AssetBalanceVO;
import com.vertex.model.vo.trading.ExchangeAccountVO;
import com.vertex.web.response.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import com.vertex.common.core.annotation.RequiresPermission;

/**
 * 交易账户管理
 */
@Tag(name = "交易账户")
@Validated
@RestController
@RequestMapping("/admin/trade/account")
@RequiredArgsConstructor
public class ExchangeAccountController {

    private final IExchangeAccountService accountService;

    @RequiresPermission("trade:account")
    @Operation(summary = "创建账户")
    @PostMapping
    public Result<Long> create(@RequestBody @Validated ExchangeAccountCreateDTO dto) {
        return Result.success(accountService.create(dto));
    }

    @RequiresPermission("trade:account")
    @Operation(summary = "更新账户")
    @PutMapping
    public Result<Void> update(@RequestBody @Validated ExchangeAccountUpdateDTO dto) {
        accountService.update(dto);
        return Result.success();
    }

    @RequiresPermission("trade:account")
    @Operation(summary = "删除账户")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        accountService.delete(id);
        return Result.success();
    }

    @RequiresPermission("trade:account")
    @Operation(summary = "账户详情")
    @GetMapping("/{id}")
    public Result<ExchangeAccountVO> getById(@PathVariable Long id) {
        return Result.success(accountService.getById(id));
    }

    @RequiresPermission("trade:account")
    @Operation(summary = "账户列表")
    @GetMapping("/list")
    public Result<List<ExchangeAccountVO>> list() {
        return Result.success(accountService.list());
    }

    @RequiresPermission("trade:account")
    @Operation(summary = "测试账户连通性")
    @PostMapping("/{id}/test")
    public Result<Boolean> testConnection(@PathVariable Long id) {
        return Result.success(accountService.testConnection(id));
    }

    @RequiresPermission("trade:account")
    @Operation(summary = "查询账户全量资产余额")
    @GetMapping("/{id}/balance")
    public Result<List<AssetBalanceVO>> getBalance(@PathVariable Long id) {
        return Result.success(accountService.getBalance(id));
    }
}

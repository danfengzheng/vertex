package com.vertex.admin.web.controller;

import com.vertex.api.role.IRoleService;
import com.vertex.common.core.page.PageResult;
import com.vertex.model.dto.system.RoleCreateDTO;
import com.vertex.model.dto.system.RoleQueryDTO;
import com.vertex.model.dto.system.RoleUpdateDTO;
import com.vertex.model.vo.system.RoleVO;
import com.vertex.web.response.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import com.vertex.common.core.annotation.RequiresPermission;

/**
 * 角色控制器
 */
@Tag(name = "角色管理")
@RestController
@RequestMapping("/admin/system/role")
@RequiredArgsConstructor
public class RoleController {

    private final IRoleService roleService;

    @RequiresPermission("system:role")
    @Operation(summary = "根据ID查询角色")
    @GetMapping("/{id}")
    public Result<RoleVO> getById(@PathVariable Long id) {
        return Result.success(roleService.getById(id));
    }

    @RequiresPermission("system:role")
    @Operation(summary = "分页查询角色")
    @GetMapping("/page")
    public Result<PageResult<RoleVO>> page(@Validated RoleQueryDTO query) {
        return Result.success(roleService.page(query));
    }

    @RequiresPermission("system:role")
    @Operation(summary = "查询所有角色")
    @GetMapping("/list")
    public Result<List<RoleVO>> listAll() {
        return Result.success(roleService.listAll());
    }

    @RequiresPermission("system:role")
    @Operation(summary = "创建角色")
    @PostMapping
    public Result<Long> create(@RequestBody @Validated RoleCreateDTO dto) {
        return Result.success(roleService.create(dto));
    }

    @RequiresPermission("system:role")
    @Operation(summary = "更新角色")
    @PutMapping
    public Result<Void> update(@RequestBody @Validated RoleUpdateDTO dto) {
        roleService.update(dto);
        return Result.success();
    }

    @RequiresPermission("system:role")
    @Operation(summary = "删除角色")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        roleService.delete(id);
        return Result.success();
    }
}

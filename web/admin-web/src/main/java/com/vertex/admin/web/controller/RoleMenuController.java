package com.vertex.admin.web.controller;

import com.vertex.api.rolemenu.IRoleMenuService;
import com.vertex.model.dto.system.RoleMenuCreateDTO;
import com.vertex.web.response.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 角色菜单关联控制器
 */
@Tag(name = "角色菜单管理")
@RestController
@RequestMapping("/admin/system/role-menu")
@RequiredArgsConstructor
public class RoleMenuController {

    private final IRoleMenuService roleMenuService;

    @Operation(summary = "根据角色ID查询菜单ID列表")
    @GetMapping("/role/{roleId}")
    public Result<List<Long>> getMenuIdsByRoleId(@PathVariable Long roleId) {
        return Result.success(roleMenuService.getMenuIdsByRoleId(roleId));
    }

    @Operation(summary = "根据菜单ID查询角色ID列表")
    @GetMapping("/menu/{menuId}")
    public Result<List<Long>> getRoleIdsByMenuId(@PathVariable Long menuId) {
        return Result.success(roleMenuService.getRoleIdsByMenuId(menuId));
    }

    @Operation(summary = "为角色分配菜单")
    @PostMapping("/assign")
    public Result<Void> assignMenusToRole(@RequestParam Long roleId, @RequestBody List<Long> menuIds) {
        roleMenuService.assignMenusToRole(roleId, menuIds);
        return Result.success();
    }

    @Operation(summary = "创建角色菜单关联")
    @PostMapping
    public Result<Long> create(@RequestBody @Validated RoleMenuCreateDTO dto) {
        return Result.success(roleMenuService.create(dto));
    }

    @Operation(summary = "删除角色菜单关联")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        roleMenuService.delete(id);
        return Result.success();
    }
}

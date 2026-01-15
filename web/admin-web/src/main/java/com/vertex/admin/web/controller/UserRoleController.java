package com.vertex.admin.web.controller;

import com.vertex.api.userrole.IUserRoleService;
import com.vertex.model.dto.system.UserRoleCreateDTO;
import com.vertex.web.response.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 用户角色关联控制器
 */
@Tag(name = "用户角色管理")
@RestController
@RequestMapping("/admin/system/user-role")
@RequiredArgsConstructor
public class UserRoleController {

    private final IUserRoleService userRoleService;

    @Operation(summary = "根据用户ID查询角色ID列表")
    @GetMapping("/user/{userId}")
    public Result<List<Long>> getRoleIdsByUserId(@PathVariable Long userId) {
        return Result.success(userRoleService.getRoleIdsByUserId(userId));
    }

    @Operation(summary = "根据角色ID查询用户ID列表")
    @GetMapping("/role/{roleId}")
    public Result<List<Long>> getUserIdsByRoleId(@PathVariable Long roleId) {
        return Result.success(userRoleService.getUserIdsByRoleId(roleId));
    }

    @Operation(summary = "为用户分配角色")
    @PostMapping("/assign")
    public Result<Void> assignRolesToUser(@RequestParam Long userId, @RequestBody List<Long> roleIds) {
        userRoleService.assignRolesToUser(userId, roleIds);
        return Result.success();
    }

    @Operation(summary = "创建用户角色关联")
    @PostMapping
    public Result<Long> create(@RequestBody @Validated UserRoleCreateDTO dto) {
        return Result.success(userRoleService.create(dto));
    }

    @Operation(summary = "删除用户角色关联")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        userRoleService.delete(id);
        return Result.success();
    }
}

package com.vertex.admin.web.controller;

import com.vertex.admin.web.config.JwtUtil;
import com.vertex.api.menu.IMenuService;
import com.vertex.api.rolemenu.IRoleMenuService;
import com.vertex.api.user.IUserService;
import com.vertex.api.userrole.IUserRoleService;
import com.vertex.common.core.context.UserContext;
import com.vertex.model.dto.system.LoginDTO;
import com.vertex.model.vo.system.LoginVO;
import com.vertex.model.vo.system.MenuVO;
import com.vertex.model.vo.system.UserVO;
import com.vertex.web.response.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 认证：登录、登出（登出由前端清除 token）
 */
@Tag(name = "认证")
@RestController
@RequestMapping("/admin/auth")
@RequiredArgsConstructor
public class AuthController {

    private final IUserService userService;
    private final JwtUtil jwtUtil;
    private final IMenuService menuService;
    private final IUserRoleService userRoleService;
    private final IRoleMenuService roleMenuService;

    @Operation(summary = "登录")
    @PostMapping("/login")
    public Result<LoginVO> login(@RequestBody @Valid LoginDTO dto) {
        UserVO user = userService.getByUsername(dto.getUsername());
        if (user == null) {
            return Result.fail(401, "用户名或密码错误");
        }
        if (user.getStatus() != null && user.getStatus() == 0) {
            return Result.fail(401, "账号已禁用");
        }
        if (Boolean.TRUE.equals(user.getLocked())) {
            return Result.fail(401, "账户已冻结，请联系管理员解冻");
        }
        if (!userService.validatePassword(dto.getUsername(), dto.getPassword())) {
            userService.recordLoginFailure(dto.getUsername());
            return Result.fail(401, "用户名或密码错误");
        }
        userService.clearLoginFailure(dto.getUsername());
        String token = jwtUtil.generateToken(user.getUsername(), user.getId(), user.getAccountType());
        return Result.success(new LoginVO(token, user));
    }

    @Operation(summary = "获取当前用户信息（需携带 token）")
    @GetMapping("/me")
    public Result<UserVO> me() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getPrincipal() == null) {
            return Result.fail(401, "未登录");
        }
        String username = (String) auth.getPrincipal();
        UserVO user = userService.getByUsername(username);
        return Result.success(user);
    }

    @Operation(summary = "获取当前用户可访问的菜单（管理员返回全部，普通用户按角色过滤）")
    @GetMapping("/menus")
    public Result<List<MenuVO>> getUserMenus() {
        // 管理员（accountType=0）返回全部启用菜单
        if (UserContext.isAdmin()) {
            return Result.success(menuService.listTree());
        }

        Long userId = UserContext.getUserId();
        if (userId == null) {
            return Result.fail(401, "未登录");
        }

        // 获取用户角色列表
        List<Long> roleIds = userRoleService.getRoleIdsByUserId(userId);
        if (roleIds == null || roleIds.isEmpty()) {
            return Result.success(List.of());
        }

        // 汇总所有角色的菜单 ID
        Set<Long> menuIds = new HashSet<>();
        for (Long roleId : roleIds) {
            List<Long> ids = roleMenuService.getMenuIdsByRoleId(roleId);
            if (ids != null) {
                menuIds.addAll(ids);
            }
        }

        return Result.success(menuService.listTreeByIds(menuIds));
    }
}

package com.vertex.service.system.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.vertex.api.permission.IPermissionService;
import com.vertex.model.entity.system.Menu;
import com.vertex.model.entity.system.RoleMenu;
import com.vertex.model.entity.system.UserRole;
import com.vertex.service.system.mapper.MenuMapper;
import com.vertex.service.system.mapper.RoleMenuMapper;
import com.vertex.service.system.mapper.UserRoleMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 权限码服务实现
 * <p>
 * 查询链路：userId → UserRole → RoleMenu → Menu.permission（跳过 null/空串）
 * 结果按 userId 缓存在进程内 ConcurrentHashMap；角色/菜单变更时主动失效。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PermissionServiceImpl implements IPermissionService {

    private final UserRoleMapper userRoleMapper;
    private final RoleMenuMapper roleMenuMapper;
    private final MenuMapper menuMapper;

    /** 进程内权限缓存：userId → 权限码集合 */
    private final ConcurrentHashMap<Long, Set<String>> cache = new ConcurrentHashMap<>();

    @Override
    public Set<String> getPermissionCodes(Long userId) {
        return cache.computeIfAbsent(userId, this::loadPermissionCodes);
    }

    @Override
    public void invalidateCache(Long userId) {
        cache.remove(userId);
        log.debug("[Permission] 已清除用户 {} 的权限缓存", userId);
    }

    @Override
    public void invalidateAllCache() {
        cache.clear();
        log.debug("[Permission] 已清除所有用户权限缓存");
    }

    // ==================== 内部加载 ====================

    private Set<String> loadPermissionCodes(Long userId) {
        // 1. 查询用户的角色 ID
        List<Long> roleIds = userRoleMapper.selectList(
                        new LambdaQueryWrapper<UserRole>().eq(UserRole::getUserId, userId))
                .stream()
                .map(UserRole::getRoleId)
                .collect(Collectors.toList());

        if (roleIds.isEmpty()) {
            return Collections.emptySet();
        }

        // 2. 查询角色关联的菜单 ID
        List<Long> menuIds = roleMenuMapper.selectList(
                        new LambdaQueryWrapper<RoleMenu>().in(RoleMenu::getRoleId, roleIds))
                .stream()
                .map(RoleMenu::getMenuId)
                .collect(Collectors.toList());

        if (menuIds.isEmpty()) {
            return Collections.emptySet();
        }

        // 3. 查询菜单的权限码（过滤 null/空串）
        Set<String> codes = menuMapper.selectList(
                        new LambdaQueryWrapper<Menu>()
                                .in(Menu::getId, menuIds)
                                .isNotNull(Menu::getPermission))
                .stream()
                .map(Menu::getPermission)
                .filter(StringUtils::hasText)
                .collect(Collectors.toCollection(HashSet::new));

        log.debug("[Permission] 用户 {} 加载权限码: {}", userId, codes);
        return codes;
    }
}

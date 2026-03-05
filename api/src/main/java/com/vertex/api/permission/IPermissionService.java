package com.vertex.api.permission;

import java.util.Set;

/**
 * 权限码查询服务
 * <p>
 * 根据用户 ID 查询其拥有的所有权限码（来自角色→菜单链路）。
 * 结果在进程内缓存；角色/菜单变更时通过 {@link #invalidateCache(Long)} 主动失效。
 */
public interface IPermissionService {

    /**
     * 获取用户的权限码集合（走缓存）
     *
     * @param userId 用户 ID
     * @return 权限码集合，如 ["system:user", "trade:account"]
     */
    Set<String> getPermissionCodes(Long userId);

    /**
     * 清除指定用户的权限缓存
     * <p>
     * 在用户角色变更（assignRolesToUser）或角色菜单变更（assignMenusToRole）后调用。
     *
     * @param userId 用户 ID
     */
    void invalidateCache(Long userId);

    /**
     * 清除所有用户的权限缓存
     * <p>
     * 用于角色菜单变更时批量失效（当无法快速确定受影响用户时使用）。
     */
    void invalidateAllCache();
}

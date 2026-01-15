package com.vertex.api.rolemenu;

import com.vertex.model.dto.system.RoleMenuCreateDTO;

import java.util.List;

/**
 * 角色菜单关联服务接口
 */
public interface IRoleMenuService {

    /**
     * 根据角色ID查询菜单ID列表
     */
    List<Long> getMenuIdsByRoleId(Long roleId);

    /**
     * 根据菜单ID查询角色ID列表
     */
    List<Long> getRoleIdsByMenuId(Long menuId);

    /**
     * 为角色分配菜单
     */
    void assignMenusToRole(Long roleId, List<Long> menuIds);

    /**
     * 创建角色菜单关联
     */
    Long create(RoleMenuCreateDTO dto);

    /**
     * 删除角色菜单关联
     */
    void delete(Long id);

    /**
     * 根据角色ID删除所有关联
     */
    void deleteByRoleId(Long roleId);

    /**
     * 根据菜单ID删除所有关联
     */
    void deleteByMenuId(Long menuId);
}

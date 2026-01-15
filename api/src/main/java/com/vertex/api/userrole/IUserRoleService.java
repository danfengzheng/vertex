package com.vertex.api.userrole;

import com.vertex.model.dto.system.UserRoleCreateDTO;

import java.util.List;

/**
 * 用户角色关联服务接口
 */
public interface IUserRoleService {

    /**
     * 根据用户ID查询角色ID列表
     */
    List<Long> getRoleIdsByUserId(Long userId);

    /**
     * 根据角色ID查询用户ID列表
     */
    List<Long> getUserIdsByRoleId(Long roleId);

    /**
     * 为用户分配角色
     */
    void assignRolesToUser(Long userId, List<Long> roleIds);

    /**
     * 创建用户角色关联
     */
    Long create(UserRoleCreateDTO dto);

    /**
     * 删除用户角色关联
     */
    void delete(Long id);

    /**
     * 根据用户ID删除所有关联
     */
    void deleteByUserId(Long userId);

    /**
     * 根据角色ID删除所有关联
     */
    void deleteByRoleId(Long roleId);
}

package com.vertex.service.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.vertex.model.entity.system.RoleMenu;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 角色菜单关联Mapper
 */
@Mapper
public interface RoleMenuMapper extends BaseMapper<RoleMenu> {

    /**
     * 按角色ID物理删除（绕过逻辑删除，用于重新分配菜单前清空旧关联，避免 uk_role_menu 重复键）
     */
    int physicalDeleteByRoleId(@Param("roleId") Long roleId);
}

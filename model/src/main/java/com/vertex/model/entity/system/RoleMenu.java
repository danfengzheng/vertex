package com.vertex.model.entity.system;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.vertex.common.core.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 角色菜单关联实体
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_role_menu")
public class RoleMenu extends BaseEntity {

    /** 关联ID */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 角色ID */
    private Long roleId;

    /** 菜单ID */
    private Long menuId;
}

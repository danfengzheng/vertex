package com.vertex.model.entity.system;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.vertex.common.core.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 菜单实体
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_menu")
public class Menu extends BaseEntity {

    /** 菜单ID */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 父菜单ID */
    private Long parentId;

    /** 菜单名称 */
    private String name;

    /** 多语言key */
    private String i18nKey;

    /** 路由路径 */
    private String path;

    /** 组件路径 */
    private String component;

    /** 图标 */
    private String icon;

    /** 菜单类型 0-目录 1-菜单 2-按钮 */
    private Integer type;

    /** 权限标识 */
    private String permission;

    /** 排序 */
    private Integer sort;

    /** 状态 0-禁用 1-启用 */
    private Integer status;
}

package com.vertex.model.vo.system;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 角色菜单关联VO
 */
@Data
public class RoleMenuVO {

    private Long id;
    private Long roleId;
    private Long menuId;
    private LocalDateTime createTime;
}

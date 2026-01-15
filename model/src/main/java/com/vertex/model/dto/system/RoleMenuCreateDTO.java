package com.vertex.model.dto.system;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 创建角色菜单关联DTO
 */
@Data
public class RoleMenuCreateDTO {

    @NotNull(message = "角色ID不能为空")
    private Long roleId;

    @NotNull(message = "菜单ID不能为空")
    private Long menuId;
}

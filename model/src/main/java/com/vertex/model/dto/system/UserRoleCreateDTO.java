package com.vertex.model.dto.system;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 创建用户角色关联DTO
 */
@Data
public class UserRoleCreateDTO {

    @NotNull(message = "用户ID不能为空")
    private Long userId;

    @NotNull(message = "角色ID不能为空")
    private Long roleId;
}

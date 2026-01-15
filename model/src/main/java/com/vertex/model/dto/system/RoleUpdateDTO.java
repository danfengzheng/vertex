package com.vertex.model.dto.system;

import jakarta.validation.constraints.*;
import lombok.Data;

/**
 * 更新角色DTO
 */
@Data
public class RoleUpdateDTO {

    @NotNull(message = "角色ID不能为空")
    private Long id;

    @Size(max = 50, message = "角色名称长度不能超过50个字符")
    private String name;

    @Size(max = 50, message = "角色编码长度不能超过50个字符")
    @Pattern(regexp = "^[a-zA-Z0-9_]+$", message = "角色编码只能包含字母、数字、下划线")
    private String code;

    @Size(max = 200, message = "描述长度不能超过200个字符")
    private String description;

    @Min(value = 0, message = "状态值不正确")
    @Max(value = 1, message = "状态值不正确")
    private Integer status;
}

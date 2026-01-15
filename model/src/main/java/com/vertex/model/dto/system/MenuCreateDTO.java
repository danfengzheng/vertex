package com.vertex.model.dto.system;

import jakarta.validation.constraints.*;
import lombok.Data;

/**
 * 创建菜单DTO
 */
@Data
public class MenuCreateDTO {

    /** 父菜单ID */
    private Long parentId;

    @NotBlank(message = "菜单名称不能为空")
    @Size(max = 50, message = "菜单名称长度不能超过50个字符")
    private String name;

    @Size(max = 100, message = "多语言key长度不能超过100个字符")
    private String i18nKey;

    @Size(max = 200, message = "路由路径长度不能超过200个字符")
    private String path;

    @Size(max = 200, message = "组件路径长度不能超过200个字符")
    private String component;

    @Size(max = 50, message = "图标长度不能超过50个字符")
    private String icon;

    @NotNull(message = "菜单类型不能为空")
    @Min(value = 0, message = "菜单类型值不正确")
    @Max(value = 2, message = "菜单类型值不正确")
    private Integer type;

    @Size(max = 100, message = "权限标识长度不能超过100个字符")
    private String permission;

    private Integer sort;

    @Min(value = 0, message = "状态值不正确")
    @Max(value = 1, message = "状态值不正确")
    private Integer status;
}

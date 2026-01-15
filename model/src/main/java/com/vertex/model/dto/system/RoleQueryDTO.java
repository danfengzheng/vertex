package com.vertex.model.dto.system;

import com.vertex.common.core.page.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 角色查询DTO
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class RoleQueryDTO extends PageQuery {

    /** 角色名称 */
    private String name;

    /** 角色编码 */
    private String code;

    /** 状态 */
    private Integer status;
}

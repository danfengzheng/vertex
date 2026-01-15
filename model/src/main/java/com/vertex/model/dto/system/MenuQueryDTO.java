package com.vertex.model.dto.system;

import com.vertex.common.core.page.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 菜单查询DTO
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class MenuQueryDTO extends PageQuery {

    /** 菜单名称 */
    private String name;

    /** 父菜单ID */
    private Long parentId;

    /** 菜单类型 */
    private Integer type;

    /** 状态 */
    private Integer status;
}

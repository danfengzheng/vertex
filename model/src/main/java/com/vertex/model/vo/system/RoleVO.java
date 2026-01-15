package com.vertex.model.vo.system;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 角色VO
 */
@Data
public class RoleVO {

    private Long id;
    private String name;
    private String code;
    private String description;
    private Integer status;
    private LocalDateTime createTime;
}

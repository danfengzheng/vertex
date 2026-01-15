package com.vertex.model.vo.system;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户角色关联VO
 */
@Data
public class UserRoleVO {

    private Long id;
    private Long userId;
    private Long roleId;
    private LocalDateTime createTime;
}

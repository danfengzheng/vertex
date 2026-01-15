package com.vertex.model.dto.system;

/**
 * UserQueryDTO
 *
 * @author eth
 * @version 1.0
 * @description
 * @date 2026/1/13 23:55
 */

import com.vertex.common.core.page.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 用户查询DTO
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class UserQueryDTO extends PageQuery {

    /** 用户名 */
    private String username;

    /** 手机号 */
    private String phone;

    /** 账户类型 */
    private Integer accountType;

    /** 状态 */
    private Integer status;

    /** 开始时间 */
    private String startTime;

    /** 结束时间 */
    private String endTime;
}

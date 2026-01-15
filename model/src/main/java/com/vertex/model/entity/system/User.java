package com.vertex.model.entity.system;

/**
 * User
 *
 * @author eth
 * @version 1.0
 * @description
 * @date 2026/1/13 23:48
 */

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.vertex.common.core.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 用户实体
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_user")
public class User extends BaseEntity {

    /** 用户ID */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 用户名 */
    private String username;

    /** 密码 */
    private String password;

    /** 昵称 */
    private String nickname;

    /** 手机号 */
    private String phone;

    /** 邮箱 */
    private String email;

    /** 头像 */
    private String avatar;

    /** 性别 0-未知 1-男 2-女 */
    private Integer gender;

    /** 账户类型 0-系统账户 1-代理账户 */
    private Integer accountType;

    /** 状态 0-禁用 1-正常 */
    private Integer status;
}

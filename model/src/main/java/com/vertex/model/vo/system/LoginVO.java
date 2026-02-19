package com.vertex.model.vo.system;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 登录响应 VO：token + 用户信息
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LoginVO {

    /** JWT 令牌 */
    private String token;

    /** 当前用户信息（不含密码） */
    private UserVO user;
}

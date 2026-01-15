package com.vertex.model.dto.system;

import jakarta.validation.constraints.*;
import lombok.Data;

/**
 * UserUpdateDTO
 *
 * @author eth
 * @version 1.0
 * @description 更新用户DTO
 * @date 2026/1/13 23:56
 */
@Data
public class UserUpdateDTO {

    @NotNull(message = "用户ID不能为空")
    private Long id;

    @Size(min = 3, max = 20, message = "用户名长度为3-20个字符")
    @Pattern(regexp = "^[a-zA-Z0-9_]+$", message = "用户名只能包含字母、数字、下划线")
    private String username;

    @Size(min = 6, max = 20, message = "密码长度为6-20个字符")
    private String password;

    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String phone;

    @Email(message = "邮箱格式不正确")
    private String email;

    private String nickname;

    private String avatar;

    @Min(value = 0, message = "性别值不正确")
    @Max(value = 2, message = "性别值不正确")
    private Integer gender;

    @Min(value = 0, message = "账户类型值不正确")
    @Max(value = 1, message = "账户类型值不正确")
    private Integer accountType;

    @Min(value = 0, message = "状态值不正确")
    @Max(value = 1, message = "状态值不正确")
    private Integer status;
}


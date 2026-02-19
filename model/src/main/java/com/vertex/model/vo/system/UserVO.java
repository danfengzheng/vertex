package com.vertex.model.vo.system;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户VO
 */
@Data
public class UserVO {

    private Long id;
    private String username;
    private String nickname;
    private String phone;
    private String email;
    private String avatar;
    private Integer gender;
    private Integer accountType;
    private Integer status;
    /** 是否已冻结 */
    private Boolean locked;
    private LocalDateTime createTime;
}
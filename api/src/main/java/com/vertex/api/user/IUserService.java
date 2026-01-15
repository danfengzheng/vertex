package com.vertex.api.user;

/**
 * IUserService
 *
 * @author eth
 * @version 1.0
 * @description
 * @date 2026/1/13 23:59
 */

import com.vertex.common.core.page.PageResult;
import com.vertex.model.dto.system.UserCreateDTO;
import com.vertex.model.dto.system.UserQueryDTO;
import com.vertex.model.dto.system.UserUpdateDTO;
import com.vertex.model.vo.system.UserVO;

/**
 * 用户服务接口
 */
public interface IUserService {

    /**
     * 根据ID查询用户
     */
    UserVO getById(Long id);

    /**
     * 分页查询用户
     */
    PageResult<UserVO> page(UserQueryDTO query);

    /**
     * 创建用户
     */
    Long create(UserCreateDTO dto);

    /**
     * 更新用户
     */
    void update(UserUpdateDTO dto);

    /**
     * 删除用户
     */
    void delete(Long id);

    /**
     * 根据用户名查询
     */
    UserVO getByUsername(String username);
}

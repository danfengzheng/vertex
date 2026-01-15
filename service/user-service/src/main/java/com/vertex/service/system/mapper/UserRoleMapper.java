package com.vertex.service.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.vertex.model.entity.system.UserRole;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户角色关联Mapper
 */
@Mapper
public interface UserRoleMapper extends BaseMapper<UserRole> {

}

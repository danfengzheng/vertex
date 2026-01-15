package com.vertex.service.system.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.vertex.api.userrole.IUserRoleService;
import com.vertex.model.dto.system.UserRoleCreateDTO;
import com.vertex.model.entity.system.UserRole;
import com.vertex.service.system.mapper.UserRoleMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 用户角色关联服务实现
 */
@Service
@RequiredArgsConstructor
public class UserRoleServiceImpl implements IUserRoleService {

    private final UserRoleMapper userRoleMapper;

    @Override
    public List<Long> getRoleIdsByUserId(Long userId) {
        List<UserRole> userRoles = userRoleMapper.selectList(
                new LambdaQueryWrapper<UserRole>()
                        .eq(UserRole::getUserId, userId)
        );
        return userRoles.stream()
                .map(UserRole::getRoleId)
                .collect(Collectors.toList());
    }

    @Override
    public List<Long> getUserIdsByRoleId(Long roleId) {
        List<UserRole> userRoles = userRoleMapper.selectList(
                new LambdaQueryWrapper<UserRole>()
                        .eq(UserRole::getRoleId, roleId)
        );
        return userRoles.stream()
                .map(UserRole::getUserId)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void assignRolesToUser(Long userId, List<Long> roleIds) {
        // 先删除该用户的所有角色关联
        deleteByUserId(userId);

        // 批量插入新的关联
        if (roleIds != null && !roleIds.isEmpty()) {
            for (Long roleId : roleIds) {
                UserRoleCreateDTO dto = new UserRoleCreateDTO();
                dto.setUserId(userId);
                dto.setRoleId(roleId);
                create(dto);
            }
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(UserRoleCreateDTO dto) {
        // 检查是否已存在
        UserRole existing = userRoleMapper.selectOne(
                new LambdaQueryWrapper<UserRole>()
                        .eq(UserRole::getUserId, dto.getUserId())
                        .eq(UserRole::getRoleId, dto.getRoleId())
        );
        if (existing != null) {
            return existing.getId();
        }

        UserRole userRole = BeanUtil.copyProperties(dto, UserRole.class);
        userRoleMapper.insert(userRole);
        return userRole.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        userRoleMapper.deleteById(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteByUserId(Long userId) {
        userRoleMapper.delete(
                new LambdaQueryWrapper<UserRole>()
                        .eq(UserRole::getUserId, userId)
        );
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteByRoleId(Long roleId) {
        userRoleMapper.delete(
                new LambdaQueryWrapper<UserRole>()
                        .eq(UserRole::getRoleId, roleId)
        );
    }
}

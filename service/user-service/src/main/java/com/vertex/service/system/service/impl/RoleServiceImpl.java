package com.vertex.service.system.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.vertex.api.role.IRoleService;
import com.vertex.common.core.GlobalError;
import com.vertex.common.core.exception.BizException;
import com.vertex.common.core.page.PageResult;
import com.vertex.model.dto.system.RoleCreateDTO;
import com.vertex.model.dto.system.RoleQueryDTO;
import com.vertex.model.dto.system.RoleUpdateDTO;
import com.vertex.model.entity.system.Role;
import com.vertex.model.vo.system.RoleVO;
import com.vertex.service.system.mapper.RoleMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 角色服务实现
 */
@Service
@RequiredArgsConstructor
public class RoleServiceImpl implements IRoleService {

    private final RoleMapper roleMapper;

    @Override
    public RoleVO getById(Long id) {
        Role role = roleMapper.selectById(id);
        if (role == null) {
            throw new BizException(GlobalError.NOT_FOUND);
        }
        return BeanUtil.copyProperties(role, RoleVO.class);
    }

    @Override
    public PageResult<RoleVO> page(RoleQueryDTO query) {
        Page<Role> page = new Page<>(query.getPageNum(), query.getPageSize());

        LambdaQueryWrapper<Role> wrapper = new LambdaQueryWrapper<>();
        wrapper.like(query.getName() != null, Role::getName, query.getName())
                .like(query.getCode() != null, Role::getCode, query.getCode())
                .eq(query.getStatus() != null, Role::getStatus, query.getStatus())
                .orderByDesc(Role::getCreateTime);

        Page<Role> result = roleMapper.selectPage(page, wrapper);
        return PageResult.of(
                result.getTotal(),
                BeanUtil.copyToList(result.getRecords(), RoleVO.class)
        );
    }

    @Override
    public List<RoleVO> listAll() {
        List<Role> roles = roleMapper.selectList(
                new LambdaQueryWrapper<Role>()
                        .eq(Role::getStatus, 1)
                        .orderByDesc(Role::getCreateTime)
        );
        return BeanUtil.copyToList(roles, RoleVO.class);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(RoleCreateDTO dto) {
        // 检查角色编码是否存在
        Role existing = roleMapper.selectOne(
                new LambdaQueryWrapper<Role>()
                        .eq(Role::getCode, dto.getCode())
        );
        if (existing != null) {
            throw new BizException("角色编码已存在");
        }

        Role role = BeanUtil.copyProperties(dto, Role.class);
        roleMapper.insert(role);
        return role.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(RoleUpdateDTO dto) {
        Role role = roleMapper.selectById(dto.getId());
        if (role == null) {
            throw new BizException(GlobalError.NOT_FOUND);
        }

        // 如果修改了角色编码，检查是否重复
        if (dto.getCode() != null && !dto.getCode().equals(role.getCode())) {
            Role existing = roleMapper.selectOne(
                    new LambdaQueryWrapper<Role>()
                            .eq(Role::getCode, dto.getCode())
            );
            if (existing != null) {
                throw new BizException("角色编码已存在");
            }
        }

        BeanUtil.copyProperties(dto, role);
        roleMapper.updateById(role);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        roleMapper.deleteById(id);
    }
}

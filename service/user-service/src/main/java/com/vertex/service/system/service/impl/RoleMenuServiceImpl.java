package com.vertex.service.system.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.vertex.api.rolemenu.IRoleMenuService;
import com.vertex.model.dto.system.RoleMenuCreateDTO;
import com.vertex.model.entity.system.RoleMenu;
import com.vertex.service.system.mapper.RoleMenuMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 角色菜单关联服务实现
 */
@Service
@RequiredArgsConstructor
public class RoleMenuServiceImpl implements IRoleMenuService {

    private final RoleMenuMapper roleMenuMapper;

    @Override
    public List<Long> getMenuIdsByRoleId(Long roleId) {
        List<RoleMenu> roleMenus = roleMenuMapper.selectList(
                new LambdaQueryWrapper<RoleMenu>()
                        .eq(RoleMenu::getRoleId, roleId)
        );
        return roleMenus.stream()
                .map(RoleMenu::getMenuId)
                .collect(Collectors.toList());
    }

    @Override
    public List<Long> getRoleIdsByMenuId(Long menuId) {
        List<RoleMenu> roleMenus = roleMenuMapper.selectList(
                new LambdaQueryWrapper<RoleMenu>()
                        .eq(RoleMenu::getMenuId, menuId)
        );
        return roleMenus.stream()
                .map(RoleMenu::getRoleId)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void assignMenusToRole(Long roleId, List<Long> menuIds) {
        // 先删除该角色的所有菜单关联
        deleteByRoleId(roleId);

        // 批量插入新的关联
        if (menuIds != null && !menuIds.isEmpty()) {
            for (Long menuId : menuIds) {
                RoleMenuCreateDTO dto = new RoleMenuCreateDTO();
                dto.setRoleId(roleId);
                dto.setMenuId(menuId);
                create(dto);
            }
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(RoleMenuCreateDTO dto) {
        // 检查是否已存在
        RoleMenu existing = roleMenuMapper.selectOne(
                new LambdaQueryWrapper<RoleMenu>()
                        .eq(RoleMenu::getRoleId, dto.getRoleId())
                        .eq(RoleMenu::getMenuId, dto.getMenuId())
        );
        if (existing != null) {
            return existing.getId();
        }

        RoleMenu roleMenu = BeanUtil.copyProperties(dto, RoleMenu.class);
        roleMenuMapper.insert(roleMenu);
        return roleMenu.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        roleMenuMapper.deleteById(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteByRoleId(Long roleId) {
        roleMenuMapper.delete(
                new LambdaQueryWrapper<RoleMenu>()
                        .eq(RoleMenu::getRoleId, roleId)
        );
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteByMenuId(Long menuId) {
        roleMenuMapper.delete(
                new LambdaQueryWrapper<RoleMenu>()
                        .eq(RoleMenu::getMenuId, menuId)
        );
    }
}

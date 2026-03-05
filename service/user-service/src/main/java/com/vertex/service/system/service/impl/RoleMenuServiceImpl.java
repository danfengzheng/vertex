package com.vertex.service.system.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.vertex.api.permission.IPermissionService;
import com.vertex.api.rolemenu.IRoleMenuService;
import com.vertex.model.dto.system.RoleMenuCreateDTO;
import com.vertex.model.entity.system.RoleMenu;
import com.vertex.service.system.mapper.RoleMenuMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 角色菜单关联服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RoleMenuServiceImpl implements IRoleMenuService {

    private final RoleMenuMapper roleMenuMapper;

    /** 菜单变更后失效权限缓存（field 注入避免循环依赖） */
    @Autowired(required = false)
    private IPermissionService permissionService;

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
        log.info("开始分配菜单权限: roleId={}, menuIds={}", roleId, menuIds);
        
        // 先物理删除该角色的所有菜单关联（表有 uk_role_menu，逻辑删除会保留行导致插入重复键）
        int deleted = roleMenuMapper.physicalDeleteByRoleId(roleId);
        log.info("已物理删除角色 {} 的 {} 条菜单关联", roleId, deleted);

        // 批量插入新的关联（去重处理，防止重复的menuId）
        if (menuIds != null && !menuIds.isEmpty()) {
            // 对菜单ID进行去重
            List<Long> uniqueMenuIds = menuIds.stream()
                    .distinct()
                    .collect(Collectors.toList());
            
            log.info("去重后的菜单ID列表: {}", uniqueMenuIds);
            
            for (Long menuId : uniqueMenuIds) {
                RoleMenuCreateDTO dto = new RoleMenuCreateDTO();
                dto.setRoleId(roleId);
                dto.setMenuId(menuId);
                create(dto);
            }
            
            log.info("菜单权限分配完成: roleId={}, menuCount={}", roleId, uniqueMenuIds.size());
        } else {
            log.info("菜单列表为空，已清空角色 {} 的所有菜单权限", roleId);
        }

        // 角色菜单变更后，清除所有用户的权限缓存（简单策略：全量失效）
        if (permissionService != null) {
            permissionService.invalidateAllCache();
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

        try {
            RoleMenu roleMenu = BeanUtil.copyProperties(dto, RoleMenu.class);
            roleMenuMapper.insert(roleMenu);
            return roleMenu.getId();
        } catch (DuplicateKeyException e) {
            // 并发冲突时，再次尝试查询（可能另一个线程已经插入）
            RoleMenu retryExisting = roleMenuMapper.selectOne(
                    new LambdaQueryWrapper<RoleMenu>()
                            .eq(RoleMenu::getRoleId, dto.getRoleId())
                            .eq(RoleMenu::getMenuId, dto.getMenuId())
            );
            if (retryExisting != null) {
                log.debug("菜单关联已存在（并发冲突），返回已有ID: roleId={}, menuId={}", 
                        dto.getRoleId(), dto.getMenuId());
                return retryExisting.getId();
            }
            // 如果仍然不存在，则抛出异常
            log.error("菜单关联插入失败（重试后仍不存在）: roleId={}, menuId={}", 
                    dto.getRoleId(), dto.getMenuId(), e);
            throw e;
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        roleMenuMapper.deleteById(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteByRoleId(Long roleId) {
        log.debug("准备删除角色 {} 的菜单权限", roleId);
        int deletedCount = roleMenuMapper.delete(
                new LambdaQueryWrapper<RoleMenu>()
                        .eq(RoleMenu::getRoleId, roleId)
        );
        log.debug("已删除 {} 条菜单权限记录 (roleId={})", deletedCount, roleId);
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

package com.vertex.service.system.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.vertex.api.menu.IMenuService;
import com.vertex.common.core.GlobalError;
import com.vertex.common.core.exception.BizException;
import com.vertex.common.core.page.PageResult;
import com.vertex.model.dto.system.MenuCreateDTO;
import com.vertex.model.dto.system.MenuQueryDTO;
import com.vertex.model.dto.system.MenuUpdateDTO;
import com.vertex.model.entity.system.Menu;
import com.vertex.model.vo.system.MenuVO;
import com.vertex.service.system.mapper.MenuMapper;
import com.vertex.common.core.config.I18nProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 菜单服务实现
 */
@Service
@RequiredArgsConstructor
public class MenuServiceImpl implements IMenuService {

    private final MenuMapper menuMapper;
    private final I18nProperties i18nProperties;

    @Override
    public MenuVO getById(Long id) {
        Menu menu = menuMapper.selectById(id);
        if (menu == null) {
            throw new BizException(GlobalError.NOT_FOUND);
        }
        return BeanUtil.copyProperties(menu, MenuVO.class);
    }

    @Override
    public PageResult<MenuVO> page(MenuQueryDTO query) {
        Page<Menu> page = new Page<>(query.getPageNum(), query.getPageSize());

        LambdaQueryWrapper<Menu> wrapper = new LambdaQueryWrapper<>();
        wrapper.like(query.getName() != null, Menu::getName, query.getName())
                .eq(query.getParentId() != null, Menu::getParentId, query.getParentId())
                .eq(query.getType() != null, Menu::getType, query.getType())
                .eq(query.getStatus() != null, Menu::getStatus, query.getStatus())
                .orderByAsc(Menu::getSort)
                .orderByDesc(Menu::getCreateTime);

        Page<Menu> result = menuMapper.selectPage(page, wrapper);
        return PageResult.of(
                result.getTotal(),
                BeanUtil.copyToList(result.getRecords(), MenuVO.class)
        );
    }

    @Override
    public List<MenuVO> listTree() {
        List<Menu> menus = menuMapper.selectList(
                new LambdaQueryWrapper<Menu>()
                        .eq(Menu::getStatus, 1)
                        .orderByAsc(Menu::getSort)
        );
        List<MenuVO> menuVOs = BeanUtil.copyToList(menus, MenuVO.class);
        return buildTree(menuVOs, 0L);
    }

    @Override
    public List<MenuVO> listByParentId(Long parentId) {
        List<Menu> menus = menuMapper.selectList(
                new LambdaQueryWrapper<Menu>()
                        .eq(Menu::getParentId, parentId)
                        .eq(Menu::getStatus, 1)
                        .orderByAsc(Menu::getSort)
        );
        return BeanUtil.copyToList(menus, MenuVO.class);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(MenuCreateDTO dto) {
        Menu menu = BeanUtil.copyProperties(dto, Menu.class);
        // 处理 i18n_key 自动拼接
        menu.setI18nKey(buildI18nKey(dto.getI18nKey(), dto.getParentId()));
        menuMapper.insert(menu);
        return menu.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(MenuUpdateDTO dto) {
        Menu menu = menuMapper.selectById(dto.getId());
        if (menu == null) {
            throw new BizException(GlobalError.NOT_FOUND);
        }

        BeanUtil.copyProperties(dto, menu);
        // 处理 i18n_key 自动拼接（如果 i18nKey 有变化）
        if (dto.getI18nKey() != null) {
            menu.setI18nKey(buildI18nKey(dto.getI18nKey(), dto.getParentId() != null ? dto.getParentId() : menu.getParentId()));
        }
        menuMapper.updateById(menu);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        // 检查是否有子菜单
        Long count = menuMapper.selectCount(
                new LambdaQueryWrapper<Menu>()
                        .eq(Menu::getParentId, id)
        );
        if (count > 0) {
            throw new BizException("存在子菜单，无法删除");
        }
        menuMapper.deleteById(id);
    }

    /**
     * 构建树形结构
     */
    private List<MenuVO> buildTree(List<MenuVO> menus, Long parentId) {
        return menus.stream()
                .filter(menu -> parentId.equals(menu.getParentId()))
                .peek(menu -> menu.setChildren(buildTree(menus, menu.getId())))
                .collect(Collectors.toList());
    }

    /**
     * 构建 i18n_key
     * 如果传入的 i18nKey 没有包含父菜单的 key，则自动拼接
     * 如果传入的 i18nKey 已经包含父菜单的 key，则直接使用
     *
     * @param i18nKey 传入的 i18n key
     * @param parentId 父菜单ID
     * @return 处理后的 i18n key
     */
    private String buildI18nKey(String i18nKey, Long parentId) {
        // 如果未开启自动拼接功能，直接返回传入的值
        if (!i18nProperties.getEnable()) {
            return i18nKey;
        }

        // 如果传入的 i18nKey 为空，直接返回
        if (!StringUtils.hasText(i18nKey)) {
            return i18nKey;
        }

        // 如果没有父菜单，直接返回传入的值
        if (parentId == null || parentId == 0) {
            return i18nKey;
        }

        // 查询父菜单
        Menu parentMenu = menuMapper.selectById(parentId);
        if (parentMenu == null || !StringUtils.hasText(parentMenu.getI18nKey())) {
            return i18nKey;
        }

        String parentI18nKey = parentMenu.getI18nKey();
        String separator = i18nProperties.getSeparator();

        // 如果传入的 i18nKey 已经以父菜单的 i18nKey 开头，直接返回
        if (i18nKey.startsWith(parentI18nKey + separator)) {
            return i18nKey;
        }

        // 自动拼接：父菜单i18nKey + 连接符 + 传入的i18nKey
        return parentI18nKey + separator + i18nKey;
    }
}

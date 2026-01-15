package com.vertex.api.menu;

import com.vertex.common.core.page.PageResult;
import com.vertex.model.dto.system.MenuCreateDTO;
import com.vertex.model.dto.system.MenuQueryDTO;
import com.vertex.model.dto.system.MenuUpdateDTO;
import com.vertex.model.vo.system.MenuVO;

import java.util.List;

/**
 * 菜单服务接口
 */
public interface IMenuService {

    /**
     * 根据ID查询菜单
     */
    MenuVO getById(Long id);

    /**
     * 分页查询菜单
     */
    PageResult<MenuVO> page(MenuQueryDTO query);

    /**
     * 查询所有菜单（树形结构）
     */
    List<MenuVO> listTree();

    /**
     * 根据父ID查询子菜单
     */
    List<MenuVO> listByParentId(Long parentId);

    /**
     * 创建菜单
     */
    Long create(MenuCreateDTO dto);

    /**
     * 更新菜单
     */
    void update(MenuUpdateDTO dto);

    /**
     * 删除菜单
     */
    void delete(Long id);
}

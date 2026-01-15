package com.vertex.api.role;

import com.vertex.common.core.page.PageResult;
import com.vertex.model.dto.system.RoleCreateDTO;
import com.vertex.model.dto.system.RoleQueryDTO;
import com.vertex.model.dto.system.RoleUpdateDTO;
import com.vertex.model.vo.system.RoleVO;

import java.util.List;

/**
 * 角色服务接口
 */
public interface IRoleService {

    /**
     * 根据ID查询角色
     */
    RoleVO getById(Long id);

    /**
     * 分页查询角色
     */
    PageResult<RoleVO> page(RoleQueryDTO query);

    /**
     * 查询所有角色
     */
    List<RoleVO> listAll();

    /**
     * 创建角色
     */
    Long create(RoleCreateDTO dto);

    /**
     * 更新角色
     */
    void update(RoleUpdateDTO dto);

    /**
     * 删除角色
     */
    void delete(Long id);
}

package com.vertex.admin.web.controller;

import com.vertex.api.menu.IMenuService;
import com.vertex.common.core.page.PageResult;
import com.vertex.model.dto.system.MenuCreateDTO;
import com.vertex.model.dto.system.MenuQueryDTO;
import com.vertex.model.dto.system.MenuUpdateDTO;
import com.vertex.model.vo.system.MenuVO;
import com.vertex.web.response.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import com.vertex.common.core.annotation.RequiresPermission;

/**
 * 菜单控制器
 */
@Tag(name = "菜单管理")
@RestController
@RequestMapping("/admin/system/menu")
@RequiredArgsConstructor
public class MenuController {

    private final IMenuService menuService;

    @RequiresPermission("system:menu")
    @Operation(summary = "根据ID查询菜单")
    @GetMapping("/{id}")
    public Result<MenuVO> getById(@PathVariable Long id) {
        return Result.success(menuService.getById(id));
    }

    @RequiresPermission("system:menu")
    @Operation(summary = "分页查询菜单")
    @GetMapping("/page")
    public Result<PageResult<MenuVO>> page(@Validated MenuQueryDTO query) {
        return Result.success(menuService.page(query));
    }

    @RequiresPermission("system:menu")
    @Operation(summary = "查询所有菜单（树形结构）")
    @GetMapping("/tree")
    public Result<List<MenuVO>> listTree() {
        return Result.success(menuService.listTree());
    }

    @RequiresPermission("system:menu")
    @Operation(summary = "根据父ID查询子菜单")
    @GetMapping("/parent/{parentId}")
    public Result<List<MenuVO>> listByParentId(@PathVariable Long parentId) {
        return Result.success(menuService.listByParentId(parentId));
    }

    @RequiresPermission("system:menu")
    @Operation(summary = "创建菜单")
    @PostMapping
    public Result<Long> create(@RequestBody @Validated MenuCreateDTO dto) {
        return Result.success(menuService.create(dto));
    }

    @RequiresPermission("system:menu")
    @Operation(summary = "更新菜单")
    @PutMapping
    public Result<Void> update(@RequestBody @Validated MenuUpdateDTO dto) {
        menuService.update(dto);
        return Result.success();
    }

    @RequiresPermission("system:menu")
    @Operation(summary = "删除菜单")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        menuService.delete(id);
        return Result.success();
    }
}

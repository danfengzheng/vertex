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

/**
 * 菜单控制器
 */
@Tag(name = "菜单管理")
@RestController
@RequestMapping("/admin/system/menu")
@RequiredArgsConstructor
public class MenuController {

    private final IMenuService menuService;

    @Operation(summary = "根据ID查询菜单")
    @GetMapping("/{id}")
    public Result<MenuVO> getById(@PathVariable Long id) {
        return Result.success(menuService.getById(id));
    }

    @Operation(summary = "分页查询菜单")
    @GetMapping("/page")
    public Result<PageResult<MenuVO>> page(@Validated MenuQueryDTO query) {
        return Result.success(menuService.page(query));
    }

    @Operation(summary = "查询所有菜单（树形结构）")
    @GetMapping("/tree")
    public Result<List<MenuVO>> listTree() {
        return Result.success(menuService.listTree());
    }

    @Operation(summary = "根据父ID查询子菜单")
    @GetMapping("/parent/{parentId}")
    public Result<List<MenuVO>> listByParentId(@PathVariable Long parentId) {
        return Result.success(menuService.listByParentId(parentId));
    }

    @Operation(summary = "创建菜单")
    @PostMapping
    public Result<Long> create(@RequestBody @Validated MenuCreateDTO dto) {
        return Result.success(menuService.create(dto));
    }

    @Operation(summary = "更新菜单")
    @PutMapping
    public Result<Void> update(@RequestBody @Validated MenuUpdateDTO dto) {
        menuService.update(dto);
        return Result.success();
    }

    @Operation(summary = "删除菜单")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        menuService.delete(id);
        return Result.success();
    }
}

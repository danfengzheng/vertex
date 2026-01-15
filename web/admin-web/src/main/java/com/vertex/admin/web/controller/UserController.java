package com.vertex.admin.web.controller;

import com.vertex.api.user.IUserService;
import com.vertex.common.core.page.PageResult;
import com.vertex.model.dto.system.UserCreateDTO;
import com.vertex.model.dto.system.UserQueryDTO;
import com.vertex.model.dto.system.UserUpdateDTO;
import com.vertex.model.vo.system.UserVO;
import com.vertex.web.response.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * 用户控制器
 */
@Tag(name = "用户管理")
@RestController
@RequestMapping("/admin/system/user")
@RequiredArgsConstructor
public class UserController {

    private final IUserService userService;

    @Operation(summary = "根据ID查询用户")
    @GetMapping("/{id}")
    public Result<UserVO> getById(@PathVariable Long id) {
        return Result.success(userService.getById(id));
    }

    @Operation(summary = "分页查询用户")
    @GetMapping("/page")
    public Result<PageResult<UserVO>> page(@Validated UserQueryDTO query) {
        return Result.success(userService.page(query));
    }

    @Operation(summary = "创建用户")
    @PostMapping
    public Result<Long> create(@RequestBody @Validated UserCreateDTO dto) {
        return Result.success(userService.create(dto));
    }

    @Operation(summary = "更新用户")
    @PutMapping
    public Result<Void> update(@RequestBody @Validated UserUpdateDTO dto) {
        userService.update(dto);
        return Result.success();
    }

    @Operation(summary = "删除用户")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        userService.delete(id);
        return Result.success();
    }
}
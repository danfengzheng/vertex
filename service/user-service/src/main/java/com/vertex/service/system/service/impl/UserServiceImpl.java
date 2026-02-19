package com.vertex.service.system.service.impl;

import at.favre.lib.crypto.bcrypt.BCrypt;
import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.vertex.api.user.IUserService;
import com.vertex.common.core.GlobalError;
import com.vertex.common.core.exception.BizException;
import com.vertex.common.core.page.PageResult;
import com.vertex.model.dto.system.UserCreateDTO;
import com.vertex.model.dto.system.UserQueryDTO;
import com.vertex.model.dto.system.UserUpdateDTO;
import com.vertex.model.entity.system.User;
import com.vertex.model.vo.system.UserVO;
import com.vertex.service.system.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 用户服务实现
 */
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements IUserService {

    private final UserMapper userMapper;

    @Override
    public UserVO getById(Long id) {
        User user = userMapper.selectById(id);
        if (user == null) {
            throw new BizException(GlobalError.USER_NOT_EXIST);
        }
        return BeanUtil.copyProperties(user, UserVO.class);
    }

    @Override
    public PageResult<UserVO> page(UserQueryDTO query) {
        Page<User> page = new Page<>(query.getPageNum(), query.getPageSize());

        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        wrapper.like(query.getUsername() != null, User::getUsername, query.getUsername())
                .like(query.getPhone() != null, User::getPhone, query.getPhone())
                .eq(query.getAccountType() != null, User::getAccountType, query.getAccountType())
                .eq(query.getStatus() != null, User::getStatus, query.getStatus())
                .orderByDesc(User::getCreateTime);

        Page<User> result = userMapper.selectPage(page, wrapper);
        return PageResult.of(
                result.getTotal(),
                BeanUtil.copyToList(result.getRecords(), UserVO.class)
        );
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(UserCreateDTO dto) {
        // 检查用户名是否存在
        User existing = userMapper.selectOne(
                new LambdaQueryWrapper<User>()
                        .eq(User::getUsername, dto.getUsername())
        );
        if (existing != null) {
            throw new BizException("用户名已存在");
        }

        User user = BeanUtil.copyProperties(dto, User.class);
        user.setPassword(hashPassword(dto.getPassword()));
        user.setLocked(false);
        user.setLoginFailCount(0);
        userMapper.insert(user);
        return user.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(UserUpdateDTO dto) {
        User user = userMapper.selectById(dto.getId());
        if (user == null) {
            throw new BizException(GlobalError.USER_NOT_EXIST);
        }

        BeanUtil.copyProperties(dto, user);
        if (StringUtils.hasText(dto.getPassword())) {
            user.setPassword(hashPassword(dto.getPassword()));
        }
        userMapper.updateById(user);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        userMapper.deleteById(id);
    }

    @Override
    public UserVO getByUsername(String username) {
        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>()
                        .eq(User::getUsername, username)
        );
        if (user == null) {
            return null;
        }
        return BeanUtil.copyProperties(user, UserVO.class);
    }

    @Override
    public boolean validatePassword(String username, String rawPassword) {
        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>()
                        .eq(User::getUsername, username)
        );
        if (user == null || !StringUtils.hasText(user.getPassword())) {
            return false;
        }
        return BCrypt.verifyer().verify(rawPassword.toCharArray(), user.getPassword()).verified;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean isLocked(String username) {
        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>()
                        .eq(User::getUsername, username)
        );
        return user != null && Boolean.TRUE.equals(user.getLocked());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void recordLoginFailure(String username) {
        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>()
                        .eq(User::getUsername, username)
        );
        if (user == null) {
            return;
        }
        int count = user.getLoginFailCount() == null ? 0 : user.getLoginFailCount();
        count++;
        user.setLoginFailCount(count);
        if (count >= 5) {
            user.setLocked(true);
        }
        userMapper.updateById(user);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void clearLoginFailure(String username) {
        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>()
                        .eq(User::getUsername, username)
        );
        if (user == null) {
            return;
        }
        user.setLoginFailCount(0);
        user.setLocked(false);
        userMapper.updateById(user);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void unfreeze(Long id) {
        User user = userMapper.selectById(id);
        if (user == null) {
            throw new BizException(GlobalError.USER_NOT_EXIST);
        }
        user.setLocked(false);
        user.setLoginFailCount(0);
        userMapper.updateById(user);
    }

    private static String hashPassword(String rawPassword) {
        return BCrypt.with(BCrypt.Version.VERSION_2A).hashToString(10, rawPassword.toCharArray());
    }
}
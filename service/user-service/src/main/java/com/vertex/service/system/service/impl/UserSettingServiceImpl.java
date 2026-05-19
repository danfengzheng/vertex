package com.vertex.service.system.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.vertex.api.usersetting.IUserSettingService;
import com.vertex.model.dto.system.UserSettingSaveDTO;
import com.vertex.model.entity.system.UserSetting;
import com.vertex.model.vo.system.UserSettingVO;
import com.vertex.service.system.mapper.UserSettingMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * 用户个人设置服务实现。
 */
@Service
@RequiredArgsConstructor
public class UserSettingServiceImpl implements IUserSettingService {

    private final UserSettingMapper userSettingMapper;

    @Override
    public UserSettingVO getByUserId(Long userId) {
        UserSettingVO vo = new UserSettingVO();
        if (userId == null) return vo;
        UserSetting entity = userSettingMapper.selectOne(
                new LambdaQueryWrapper<UserSetting>().eq(UserSetting::getUserId, userId));
        if (entity != null) {
            vo.setMaxTradeCapital(entity.getMaxTradeCapital());
        }
        return vo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void save(Long userId, UserSettingSaveDTO dto) {
        if (userId == null) return;
        UserSetting existing = userSettingMapper.selectOne(
                new LambdaQueryWrapper<UserSetting>().eq(UserSetting::getUserId, userId));
        // 归一化：<= 0 视为清空（不限制）
        BigDecimal max = dto.getMaxTradeCapital();
        if (max != null && max.compareTo(BigDecimal.ZERO) <= 0) {
            max = null;
        }
        if (existing == null) {
            UserSetting setting = new UserSetting();
            setting.setUserId(userId);
            setting.setMaxTradeCapital(max);
            userSettingMapper.insert(setting);
        } else {
            existing.setMaxTradeCapital(max);
            userSettingMapper.updateById(existing);
        }
    }

    @Override
    public BigDecimal getMaxTradeCapital(Long userId) {
        if (userId == null) return null;
        UserSetting entity = userSettingMapper.selectOne(
                new LambdaQueryWrapper<UserSetting>().eq(UserSetting::getUserId, userId));
        if (entity == null) return null;
        BigDecimal max = entity.getMaxTradeCapital();
        return (max != null && max.compareTo(BigDecimal.ZERO) > 0) ? max : null;
    }
}

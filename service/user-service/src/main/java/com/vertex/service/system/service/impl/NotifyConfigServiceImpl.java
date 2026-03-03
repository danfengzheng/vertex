package com.vertex.service.system.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.vertex.api.notify.INotifyConfigService;
import com.vertex.model.dto.system.NotifyConfigSaveDTO;
import com.vertex.model.entity.system.NotifyConfig;
import com.vertex.model.vo.system.NotifyConfigVO;
import com.vertex.service.system.mapper.NotifyConfigMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class NotifyConfigServiceImpl implements INotifyConfigService {

    private final NotifyConfigMapper notifyConfigMapper;

    @Override
    public NotifyConfigVO getByUserId(Long userId, String channel) {
        NotifyConfig config = notifyConfigMapper.selectOne(
                new LambdaQueryWrapper<NotifyConfig>()
                        .eq(NotifyConfig::getUserId, userId)
                        .eq(NotifyConfig::getChannel, channel)
        );
        if (config == null) {
            NotifyConfigVO vo = new NotifyConfigVO();
            vo.setChannel(channel);
            vo.setEnabled(0);
            return vo;
        }
        return BeanUtil.copyProperties(config, NotifyConfigVO.class);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void save(Long userId, String channel, NotifyConfigSaveDTO dto) {
        NotifyConfig existing = notifyConfigMapper.selectOne(
                new LambdaQueryWrapper<NotifyConfig>()
                        .eq(NotifyConfig::getUserId, userId)
                        .eq(NotifyConfig::getChannel, channel)
        );
        if (existing == null) {
            NotifyConfig config = new NotifyConfig();
            config.setUserId(userId);
            config.setChannel(channel);
            config.setChatId(dto.getChatId());
            config.setEnabled(dto.getEnabled() != null ? dto.getEnabled() : 1);
            notifyConfigMapper.insert(config);
        } else {
            existing.setChatId(dto.getChatId());
            if (dto.getEnabled() != null) {
                existing.setEnabled(dto.getEnabled());
            }
            notifyConfigMapper.updateById(existing);
        }
    }
}

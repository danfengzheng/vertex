package com.vertex.api.notify;

import com.vertex.model.dto.system.NotifyConfigSaveDTO;
import com.vertex.model.vo.system.NotifyConfigVO;

/**
 * 用户通知配置服务接口
 */
public interface INotifyConfigService {

    /**
     * 获取指定用户指定渠道的通知配置
     *
     * @param userId  用户ID
     * @param channel 渠道（如 "TELEGRAM"）
     * @return 配置 VO，不存在时返回默认（enabled=0）
     */
    NotifyConfigVO getByUserId(Long userId, String channel);

    /**
     * 保存（新增或更新）用户通知配置
     *
     * @param userId  用户ID
     * @param channel 渠道
     * @param dto     配置内容
     */
    void save(Long userId, String channel, NotifyConfigSaveDTO dto);
}

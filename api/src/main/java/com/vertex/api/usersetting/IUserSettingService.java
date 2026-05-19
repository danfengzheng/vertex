package com.vertex.api.usersetting;

import com.vertex.model.dto.system.UserSettingSaveDTO;
import com.vertex.model.vo.system.UserSettingVO;

import java.math.BigDecimal;

/**
 * 用户个人设置服务接口（跨模块 RPC）。
 */
public interface IUserSettingService {

    /**
     * 获取指定用户的个人设置（不存在时返回各字段为 null 的空 VO）。
     */
    UserSettingVO getByUserId(Long userId);

    /**
     * 保存（新增或更新）用户个人设置。
     */
    void save(Long userId, UserSettingSaveDTO dto);

    /**
     * 仅取「单笔开仓最大使用资金」字段，供 TradeExecutionService 在仓位计算时截断使用。
     * 返回 null 或 &lt;= 0 表示未启用限制。
     */
    BigDecimal getMaxTradeCapital(Long userId);
}

package com.vertex.model.vo.system;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 用户个人设置 VO
 */
@Data
public class UserSettingVO {

    /**
     * 单笔开仓最大使用资金（U/USDT）。
     * &gt; 0 时启用，&lt;=0 或 null 时不限制。
     */
    private BigDecimal maxTradeCapital;
}

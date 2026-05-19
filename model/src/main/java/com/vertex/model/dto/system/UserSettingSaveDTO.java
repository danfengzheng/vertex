package com.vertex.model.dto.system;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 保存用户个人设置 DTO
 */
@Data
public class UserSettingSaveDTO {

    /**
     * 单笔开仓最大使用资金（U/USDT）。
     * <ul>
     *   <li>&gt; 0：开仓本金（保证金）超过此值时截断为此值。</li>
     *   <li>null / &lt;=0：不启用该限制。</li>
     * </ul>
     */
    private BigDecimal maxTradeCapital;
}

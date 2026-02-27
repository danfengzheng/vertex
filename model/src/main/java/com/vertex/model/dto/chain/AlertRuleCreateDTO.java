package com.vertex.model.dto.chain;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 创建告警规则请求 DTO
 */
@Data
public class AlertRuleCreateDTO {

    @NotBlank(message = "规则名称不能为空")
    private String name;

    /** 目标链：BNB / SOLANA / ALL */
    private String chain = "ALL";

    @NotNull(message = "最低评分不能为空")
    @Min(0) @Max(100)
    private Integer minScore;

    private BigDecimal minMarketCapUsd;
    private BigDecimal minLiquidityUsd;
    private Integer minHolderCount;
    private BigDecimal maxTop10HolderPct;

    /** 通知渠道 JSON 字符串，如 ["telegram"] */
    private String notifyChannels = "[\"telegram\"]";

    /** 是否要求流动性锁定：0/1/null */
    private Integer requireLiquidityLocked;

    private String description;
}

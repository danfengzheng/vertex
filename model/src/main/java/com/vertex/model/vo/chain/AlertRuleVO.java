package com.vertex.model.vo.chain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 告警规则展示 VO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AlertRuleVO implements Serializable {

    private Long id;
    private String name;
    private String chain;
    private Integer minScore;
    private BigDecimal minMarketCapUsd;
    private BigDecimal minLiquidityUsd;
    private Integer minHolderCount;
    private BigDecimal maxTop10HolderPct;
    private String notifyChannels;
    private Integer requireLiquidityLocked;
    private Integer enabled;
    private String description;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}

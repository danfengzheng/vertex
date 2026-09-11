package com.vertex.service.mm.grid.service;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 自适应调整建议。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StepAdjustment {

    private long strategyId;

    /** 是否需要调整（false = 保持不变）*/
    private boolean shouldAdjust;

    private BigDecimal newStepPct;
    private BigDecimal newCenterPrice;

    /** RANGING / WEAK / MODERATE / STRONG */
    private String trendState;

    /** NORMAL / ASYMMETRIC / PAUSE_LONG / PAUSE_SHORT */
    private String suggestedAction;

    /** 用于日志和 grid_event 记录 */
    private String reason;

    /** 决策依据：当前 ADX / vol_ratio */
    private BigDecimal currentAdx;
    private BigDecimal currentVolRatio;
    private BigDecimal minimumSafeStep;
}

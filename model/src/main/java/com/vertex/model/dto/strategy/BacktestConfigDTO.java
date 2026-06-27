package com.vertex.model.dto.strategy;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 策略回测配置
 */
@Data
public class BacktestConfigDTO implements Serializable {

    @NotNull(message = "策略ID不能为空")
    private Long strategyId;

    @NotNull(message = "开始时间不能为空")
    private Long startTime;

    @NotNull(message = "结束时间不能为空")
    private Long endTime;

    /** 初始资金，默认 10000 USDT */
    private BigDecimal initialCapital = new BigDecimal("10000");

    /** 每次交易仓位比例 0-1，默认 1.0 (全仓) */
    private BigDecimal positionRatio = BigDecimal.ONE;

    /** 交易手续费率，默认 0.001 (0.1%) */
    private BigDecimal feeRate = new BigDecimal("0.001");

    /**
     * 是否启用 AI 批量分析（异步执行，不阻塞回测结果返回）。
     * 仅当 vertex.ai.gemini.enabled=true 时实际生效。
     */
    private Boolean enableAiAnalysis = Boolean.FALSE;

    /**
     * 强制重跑：忽略 RocksDB 中的缓存结果，重新计算回测。
     * 默认 false → 命中缓存时秒返回。
     */
    private Boolean forceRefresh = Boolean.FALSE;
}

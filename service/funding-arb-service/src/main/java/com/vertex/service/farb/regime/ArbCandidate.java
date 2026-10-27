package com.vertex.service.farb.regime;

import lombok.Builder;
import lombok.Data;

/**
 * Regime 扫描产出的入场候选。按 score 降序排列，供 PairExecutor 消费。
 */
@Data
@Builder
public class ArbCandidate {

    private String symbol;

    /** 综合评分（0-100+）*/
    private double score;

    /** 该 symbol 的完整 metrics（前端展示用）*/
    private RegimeMetrics metrics;

    /** 预期单对净收益率（%），基于当前 funding 和 fee 估算 */
    private double expectedNetYieldPct;

    /** 预期年化收益率（%）*/
    private double expectedAnnualizedYieldPct;

    /** 建议入场原因（前端 UI 展示：如"funding regime 强 + basis 有利"）*/
    private String reason;

    /** 生成候选的时间戳 */
    private long generatedAt;
}

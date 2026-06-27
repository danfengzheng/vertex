package com.vertex.model.vo.ai;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * AI 仪表盘里「实时信号 AI 分析」的一行展示数据。
 * <p>
 * 由 {@code AiAnalysisStore} 列表查询填 {@code signalId} + {@code analysis}；
 * Controller 层做 enrichment，从 {@code SignalMapper} 补充信号上下文
 * （strategy/exchange/symbol/interval/...）。AI 端不重跑，仅做展示。
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiSignalAnalysisRow implements Serializable {

    private Long signalId;

    // ─── 信号上下文（来自 SignalMapper） ─────────────────────
    private Long strategyId;
    private String strategyName;
    private String exchange;
    private String symbol;
    private String interval;
    private String signalType;
    private Integer signalStrength;
    private Long signalTime;
    private String price;

    /** AI 分析结果（核心字段）*/
    private AiSignalAnalysis analysis;
}

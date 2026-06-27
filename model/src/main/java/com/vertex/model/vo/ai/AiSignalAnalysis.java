package com.vertex.model.vo.ai;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * AI 对单条信号的分析结果（实时 + 回测共用结构）。
 * <p>
 * 由 GeminiClient 通过 responseSchema 强制返回符合本结构的 JSON。
 * 完全不参与交易决策，仅作展示/统计/对账用。
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiSignalAnalysis implements Serializable {

    /** AI 对信号方向的置信度（0-1） */
    private Double confidence;

    /**
     * AI 自身的方向判断与信号方向是否一致：
     * <ul>
     *   <li>ALIGNED ─ AI 判断与信号方向一致</li>
     *   <li>NEUTRAL ─ AI 无明确方向</li>
     *   <li>DIVERGED ─ AI 判断与信号方向相反</li>
     * </ul>
     */
    private String alignment;

    /**
     * AI 识别的市场状态：
     * <ul>
     *   <li>TRENDING ─ 趋势</li>
     *   <li>RANGING ─ 震荡</li>
     *   <li>VOLATILE ─ 剧烈波动</li>
     *   <li>CALM ─ 平静</li>
     * </ul>
     */
    private String marketRegime;

    /** AI 识别出的关键支撑因素（2-4 条） */
    private List<String> keyFactors;

    /** AI 识别出的潜在风险（0-3 条） */
    private List<String> risks;

    /**
     * AI 给出的建议（仅参考，不自动执行）：
     * ENTER_FULL / ENTER_HALF / ENTER_WITH_TIGHT_STOP / OBSERVE / SKIP
     */
    private String suggestedAction;

    /** 一句话总结 */
    private String summary;

    /** 分析所用 AI 模型 */
    private String model;

    /** 完成时间戳（ms, UTC） */
    private Long analyzedAt;

    /** 调用耗时（ms） */
    private Long durationMs;

    /** 失败原因（成功时为 null） */
    private String errorMessage;
}

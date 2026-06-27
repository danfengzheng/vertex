package com.vertex.model.vo.ai;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * AI 对回测中单笔交易（TradeRecord）的分析结果。
 * <p>
 * 与 AiSignalAnalysis 结构类似，但视角是「事后分析」：
 * 已知 trade 的入场价、出场价、盈亏、出场原因，AI 解读 trade 成败原因。
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiTradeAnalysis implements Serializable {

    /** 在 BacktestResultVO.trades 中的索引 */
    private Integer tradeIndex;

    /** 入场时间（ms, UTC） */
    private Long entryTime;

    /** 出场时间（ms, UTC） */
    private Long exitTime;

    /** AI 对该笔 trade 质量的评分（0-1，0=明显失败的入场，1=完美交易） */
    private Double quality;

    /**
     * AI 判断的成败原因类型：
     * GOOD_ENTRY / LATE_ENTRY / FALSE_SIGNAL / GOOD_EXIT / EARLY_EXIT / BAD_STOP_LOSS / LUCKY_PROFIT
     */
    private String verdict;

    /** AI 识别的入场时的关键因素 */
    private List<String> entryFactors;

    /** AI 识别的出场表现（止盈/止损是否合理） */
    private List<String> exitFactors;

    /** AI 给出的策略改进建议（可空） */
    private List<String> improvements;

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

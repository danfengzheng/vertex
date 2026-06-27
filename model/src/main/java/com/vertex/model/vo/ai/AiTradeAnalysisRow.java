package com.vertex.model.vo.ai;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * AI 仪表盘里「回测 Trade AI 分析」的一行展示数据。
 * <p>
 * RocksDB key 格式：{@code ai:bt:trade:{cacheKey}:{tradeIndex 4位补零}}。
 * Controller 层会用 cached BacktestResultVO 给 row 补 strategy / exchange / symbol
 * 等上下文。
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiTradeAnalysisRow implements Serializable {

    /** 回测缓存指纹（取自 RocksDB key）*/
    private String cacheKey;
    /** trade 在 BacktestResultVO.trades 数组中的下标 */
    private Integer tradeIndex;

    // ─── 回测上下文（来自 cached BacktestResultVO，可能为 null）─────
    private Long strategyId;
    private String strategyName;
    private String exchange;
    private String symbol;
    private String interval;

    // ─── trade 摘要（来自 cached BacktestResultVO.trades[i]，可能为 null）─
    private Long entryTime;
    private Long exitTime;
    private String type;        // LONG / SHORT
    private String profit;
    private String profitPercent;

    /** AI 分析结果（核心字段）*/
    private AiTradeAnalysis analysis;
}

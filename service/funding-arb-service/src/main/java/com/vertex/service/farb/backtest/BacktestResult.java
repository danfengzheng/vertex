package com.vertex.service.farb.backtest;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 回测结果 —— 统计指标 + equity 曲线 + 单笔明细。
 */
@Data
@Builder
public class BacktestResult {

    // ─── 输入回显 ───────────────────────────────────
    private BacktestConfig config;
    private long startTimeMs;
    private long endTimeMs;
    private int universeSize;

    // ─── 核心指标 ────────────────────────────────
    private BigDecimal initialCapital;
    private BigDecimal finalCapital;
    private BigDecimal totalNetPnl;
    private BigDecimal totalFundingPnl;
    private BigDecimal totalBasisPnl;
    private BigDecimal totalFeePaid;
    /** 期末资金 / 初始资金 - 1 */
    private BigDecimal totalReturnPct;
    /** 年化收益率 */
    private BigDecimal annualizedReturnPct;
    /** Sharpe（简化：mean(daily) / std(daily) × sqrt(365)） */
    private BigDecimal sharpe;
    /** 最大回撤 */
    private BigDecimal maxDrawdownPct;

    // ─── 交易统计 ────────────────────────────────
    private int totalPairsOpened;
    private int totalPairsClosed;
    private int winCount;
    private int lossCount;
    private BigDecimal winRatePct;
    private BigDecimal avgPairNetPnl;
    private BigDecimal avgHoldingHours;
    private BigDecimal avgSettlementsPerPair;

    // ─── 分布 ────────────────────────────────────
    /** 出场触发类型分布：REGIME_BREAK / BASIS_BREACH / TAKE_PROFIT / MAX_HOLD_TIMEOUT / EMERGENCY */
    private Map<String, Integer> closeTriggerDistribution;
    /** 单币种 top 10 净 PnL */
    private List<SymbolPerf> topSymbols;
    /** 单币种 bottom 10 */
    private List<SymbolPerf> bottomSymbols;

    // ─── 曲线 ────────────────────────────────────
    /** Equity curve（时间戳升序，每次结算或平仓事件一个点）*/
    private List<EquityPoint> equityCurve;
    /** 单笔明细（最多 500 条，避免响应过大）*/
    private List<PairTrade> trades;

    /** 数据完整度告警 */
    private List<String> warnings;

    @Data
    @Builder
    public static class EquityPoint {
        private long ts;
        private BigDecimal equity;
        private BigDecimal drawdownPct;
    }

    @Data
    @Builder
    public static class PairTrade {
        private String symbol;
        private long entryTs;
        private long exitTs;
        private int holdHours;
        private int settlementCount;
        private BigDecimal fundingPnl;
        private BigDecimal basisPnl;
        private BigDecimal feePaid;
        private BigDecimal netPnl;
        private String closeTrigger;
    }

    @Data
    @Builder
    public static class SymbolPerf {
        private String symbol;
        private int pairCount;
        private BigDecimal totalNetPnl;
        private BigDecimal winRatePct;
        private BigDecimal avgNetPnl;
    }
}

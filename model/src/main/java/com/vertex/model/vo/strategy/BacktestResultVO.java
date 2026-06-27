package com.vertex.model.vo.strategy;

import lombok.Builder;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;

/**
 * 策略回测结果
 */
@Data
@Builder
public class BacktestResultVO implements Serializable {

    /** 策略ID */
    private Long strategyId;

    /** 策略名称 */
    private String strategyName;

    /** 回测时间范围 */
    private Long startTime;
    private Long endTime;

    /** 初始资金 */
    private BigDecimal initialCapital;

    /** 最终资金 */
    private BigDecimal finalCapital;

    /** 总收益 */
    private BigDecimal totalProfit;

    /** 收益率 (%) */
    private BigDecimal returnRate;

    /** 总交易次数 */
    private Integer totalTrades;

    /** 盈利交易次数 */
    private Integer winningTrades;

    /** 亏损交易次数 */
    private Integer losingTrades;

    /** 胜率 (%) */
    private BigDecimal winRate;

    /** 盈亏比 (avgWin / avgLoss) */
    private BigDecimal profitLossRatio;

    /** 最大回撤 (%) */
    private BigDecimal maxDrawdown;

    /** 最大回撤持续K线根数 */
    private Integer maxDrawdownDuration;

    /** 夏普比率 */
    private BigDecimal sharpeRatio;

    /** 交易记录列表 */
    private List<TradeRecord> trades;

    /** 资金曲线数据点 */
    private List<EquityPoint> equityCurve;

    // ─── AI 分析 / 缓存相关字段 ─────────────────────────────────────────

    /**
     * 回测结果缓存 key（SHA256 hex 截取前 16 位作为短指纹，前端可展示）。
     * 由「策略白名单字段 + 回测配置」内容哈希生成，相同配置稳定一致。
     */
    private String cacheKey;

    /** 本次回测结果是否来自缓存（true=命中缓存，毫秒返回；false=本次重新计算）。 */
    private Boolean cached;

    /** 缓存创建时间戳（ms, UTC）；命中缓存时返回原始计算时间，未命中时为本次结果时间。 */
    private Long cachedAt;

    /**
     * AI 分析进度状态（与 AiBacktestAnalysisProgress.Status 对齐）：
     * NULL/PENDING/RUNNING/COMPLETED/FAILED/CANCELLED。
     */
    private String aiAnalysisStatus;

    @Data
    @Builder
    public static class TradeRecord implements Serializable {
        private Long entryTime;
        private Long exitTime;
        private String type;
        private BigDecimal entryPrice;
        private BigDecimal exitPrice;
        private BigDecimal quantity;
        private BigDecimal profit;
        private BigDecimal profitPercent;
        /** 平仓原因：SIGNAL | STOP_LOSS | TAKE_PROFIT | END_OF_BACKTEST */
        private String exitReason;
    }

    @Data
    @Builder
    public static class EquityPoint implements Serializable {
        private Long time;
        private BigDecimal equity;
    }
}

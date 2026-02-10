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
    }

    @Data
    @Builder
    public static class EquityPoint implements Serializable {
        private Long time;
        private BigDecimal equity;
    }
}

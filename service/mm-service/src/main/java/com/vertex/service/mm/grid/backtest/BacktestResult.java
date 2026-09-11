package com.vertex.service.mm.grid.backtest;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * Grid 回测结果。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BacktestResult {

    private Long strategyId;
    private String symbol;
    private Long startTime;
    private Long endTime;
    private String klineInterval;

    // ─── 核心指标 ─────────────────────────────
    private BigDecimal initialCapital;
    private BigDecimal finalCapital;
    private BigDecimal totalReturn;
    private BigDecimal totalReturnPct;

    private BigDecimal totalArbitragePnl;      // 网格套利部分
    private BigDecimal totalDirectionalPnl;    // 方向敞口部分
    private BigDecimal totalFeePaid;

    private int totalRoundTrips;               // 完成的完整套利次数
    private int totalOpenFills;
    private int totalCloseFills;

    // ─── 保护层触发 ───────────────────────────
    private int layer2Triggers;
    private int layer3Triggers;
    private int layer4Triggers;

    // ─── 回撤 ─────────────────────────────────
    private BigDecimal maxDrawdown;
    private BigDecimal maxDrawdownPct;
    private long maxDrawdownDurationMs;

    // ─── 敞口 ─────────────────────────────────
    private BigDecimal maxLongQty;
    private BigDecimal maxShortQty;
    private BigDecimal maxNetQty;

    // ─── 时间序列（前端画曲线用）─────────────
    private List<EquityPoint> equityCurve;

    // ─── 日志（诊断用）────────────────────────
    private List<String> events;

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class EquityPoint {
        private long timeMs;
        private BigDecimal capital;
        private BigDecimal arbPnl;
        private BigDecimal dirPnl;
        private BigDecimal longQty;
        private BigDecimal shortQty;
        private String currentLayer;
    }
}

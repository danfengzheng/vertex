package com.vertex.model.vo.strategy;

import com.vertex.model.dto.strategy.StrategyIndicatorConfig;
import com.vertex.model.entity.quote.KLineInterval;
import com.vertex.model.entity.trading.ExecutionMode;
import com.vertex.model.entity.trading.MarginType;
import com.vertex.model.entity.trading.PositionSizing;
import com.vertex.model.entity.trading.TradeMode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 策略 VO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StrategyVO implements Serializable {

    private Long id;
    private String name;
    private String description;
    private String exchange;
    private String symbol;
    private KLineInterval interval;
    private List<StrategyIndicatorConfig> indicatorConfigs;
    /** 出场指标配置 */
    private List<StrategyIndicatorConfig> exitIndicatorConfigs;
    /** 最大持仓K线根数，超过后强制平仓，null=不限 */
    private Integer maxHoldingBars;
    private Integer enabled;

    // ─── 交易配置 ───────────────────────────────────
    private Integer autoTrade;
    /** 自动交易最低信号强度门槛（0-100） */
    private Integer minSignalStrength;
    private TradeMode tradeMode;
    private ExecutionMode executionMode;
    private Long accountId;
    private PositionSizing positionSizing;
    private BigDecimal tradeQuantity;
    private BigDecimal positionRatio;
    private BigDecimal initialCapital;
    private BigDecimal stopLossPct;
    private BigDecimal takeProfitPct;
    private BigDecimal feeRate;

    // ─── 合约配置 ───────────────────────────────────
    private Integer leverage;
    private MarginType marginType;

    // ─── 止损止盈配置 ────────────────────────────────
    /** ATR 止损倍数（如 2.0），设置后优先于固定止损百分比 */
    private BigDecimal atrStopMultiplier;
    /** ATR 止盈倍数（如 3.0），设置后优先于固定止盈百分比 */
    private BigDecimal atrTakeProfitMultiplier;

    // ─── 移动ATR止损配置 ──────────────────────────────
    private BigDecimal initialStopMultiplier;
    private BigDecimal breakevenActivationMultiplier;
    private BigDecimal trailingActivationMultiplier;
    private BigDecimal trailingDistanceMultiplier;

    /** ATR止损专用K线周期（留空则使用策略默认周期） */
    private KLineInterval atrInterval;

    /** 峰值回撤止损百分比（如 5.0 = 5%），从最高价(多)/最低价(空)回撤超过此值时止损，null=不启用 */
    private BigDecimal trailingDropPct;

    /** 日亏损限制百分比（如 5.0 = 5%），当日累计亏损超过此值时暂停交易 24 小时，null=不启用 */
    private BigDecimal dailyLossLimitPct;

    /** 交易暂停截止时间（UTC），用于前端展示"暂停中"状态，null=未暂停 */
    private LocalDateTime tradingPausedUntil;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}

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
    private Integer enabled;

    // ─── 交易配置 ───────────────────────────────────
    private Integer autoTrade;
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

    // ─── 止损配置 ───────────────────────────────────
    /** ATR 止损倍数（如 2.0），设置后优先于固定止损百分比 */
    private BigDecimal atrStopMultiplier;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}

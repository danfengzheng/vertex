package com.vertex.model.vo.strategy;

import com.vertex.model.dto.strategy.StrategyIndicatorConfig;
import com.vertex.model.entity.quote.KLineInterval;
import com.vertex.model.entity.trading.ExecutionMode;
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

    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}

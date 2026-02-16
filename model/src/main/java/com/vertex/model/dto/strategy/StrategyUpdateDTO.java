package com.vertex.model.dto.strategy;

import com.vertex.model.entity.quote.KLineInterval;
import com.vertex.model.entity.trading.ExecutionMode;
import com.vertex.model.entity.trading.TradeMode;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 策略更新参数
 */
@Data
public class StrategyUpdateDTO {

    @NotNull(message = "策略ID不能为空")
    private Long id;

    private String name;

    private String description;

    private String exchange;

    private String symbol;

    private KLineInterval interval;

    private List<StrategyIndicatorConfig> indicatorConfigs;

    // ─── 交易配置（可选） ───────────────────────────
    private Integer autoTrade;
    private TradeMode tradeMode;
    private ExecutionMode executionMode;
    private Long accountId;
    private BigDecimal tradeQuantity;
    private BigDecimal stopLossPct;
    private BigDecimal takeProfitPct;
    private BigDecimal feeRate;
}

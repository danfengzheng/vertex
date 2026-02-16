package com.vertex.model.dto.strategy;

import com.vertex.model.entity.quote.KLineInterval;
import com.vertex.model.entity.trading.ExecutionMode;
import com.vertex.model.entity.trading.TradeMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 策略创建参数
 */
@Data
public class StrategyCreateDTO {

    @NotBlank(message = "策略名称不能为空")
    private String name;

    private String description;

    @NotBlank(message = "交易所不能为空")
    private String exchange;

    @NotBlank(message = "交易对不能为空")
    private String symbol;

    @NotNull(message = "K线周期不能为空")
    private KLineInterval interval;

    @NotEmpty(message = "指标配置不能为空")
    private List<StrategyIndicatorConfig> indicatorConfigs;

    // ─── 交易配置（可选） ───────────────────────────
    /** 是否开启自动交易 */
    private Integer autoTrade;
    /** 交易模式 AUTO/MANUAL */
    private TradeMode tradeMode;
    /** 执行模式 LIVE/PAPER */
    private ExecutionMode executionMode;
    /** 关联交易账户ID */
    private Long accountId;
    /** 每次交易数量 */
    private BigDecimal tradeQuantity;
    /** 止损百分比 */
    private BigDecimal stopLossPct;
    /** 止盈百分比 */
    private BigDecimal takeProfitPct;
}

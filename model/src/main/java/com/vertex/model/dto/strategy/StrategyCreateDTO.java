package com.vertex.model.dto.strategy;

import com.vertex.model.entity.quote.KLineInterval;
import com.vertex.model.entity.trading.ExecutionMode;
import com.vertex.model.entity.trading.MarginType;
import com.vertex.model.entity.trading.PositionSizing;
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
    /** 自动交易最低信号强度门槛（0-100） */
    private Integer minSignalStrength;
    /** 交易模式 AUTO/MANUAL */
    private TradeMode tradeMode;
    /** 执行模式 LIVE/PAPER */
    private ExecutionMode executionMode;
    /** 关联交易账户ID */
    private Long accountId;
    /** 仓位计算模式 FIXED/PERCENT */
    private PositionSizing positionSizing;
    /** 每次交易数量（FIXED 模式） */
    private BigDecimal tradeQuantity;
    /** 仓位比例 0-1（PERCENT 模式，默认 1.0 = 全仓） */
    private BigDecimal positionRatio;
    /** 模拟初始资金（PERCENT + PAPER 模式） */
    private BigDecimal initialCapital;
    /** 止损百分比 */
    private BigDecimal stopLossPct;
    /** 止盈百分比 */
    private BigDecimal takeProfitPct;
    /** 手续费率 */
    private BigDecimal feeRate;

    // ─── 合约配置（可选） ───────────────────────────
    /** 杠杆倍数（合约账户生效，1-125） */
    private Integer leverage;
    /** 保证金模式 ISOLATED/CROSS */
    private MarginType marginType;

    // ─── 止损止盈配置（可选） ─────────────────────────
    /** ATR 止损倍数（如 2.0），设置后优先于固定止损百分比 */
    private BigDecimal atrStopMultiplier;
    /** ATR 止盈倍数（如 3.0），设置后优先于固定止盈百分比 */
    private BigDecimal atrTakeProfitMultiplier;
}

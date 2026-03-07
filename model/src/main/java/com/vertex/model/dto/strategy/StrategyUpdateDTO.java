package com.vertex.model.dto.strategy;

import com.vertex.model.entity.quote.KLineInterval;
import com.vertex.model.entity.trading.ExecutionMode;
import com.vertex.model.entity.trading.MarginType;
import com.vertex.model.entity.trading.PositionSizing;
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
    /** 自动交易最低信号强度门槛（0-100），null 时清空（代码默认 60） */
    private Integer minSignalStrength;
    private TradeMode tradeMode;
    private ExecutionMode executionMode;
    private Long accountId;
    /** 仓位计算模式 FIXED/PERCENT */
    private PositionSizing positionSizing;
    private BigDecimal tradeQuantity;
    /** 仓位比例 0-1（PERCENT 模式） */
    private BigDecimal positionRatio;
    /** 模拟初始资金（PERCENT + PAPER 模式） */
    private BigDecimal initialCapital;
    private BigDecimal stopLossPct;
    private BigDecimal takeProfitPct;
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

    // ─── 移动ATR止损配置（四参数联动） ────────────────────
    /** 阶段1：初始止损倍数（如 3.5） */
    private BigDecimal initialStopMultiplier;
    /** 阶段2：激活保本的ATR距离倍数（如 1.8） */
    private BigDecimal breakevenActivationMultiplier;
    /** 阶段3：激活追踪的ATR距离倍数（如 2.5） */
    private BigDecimal trailingActivationMultiplier;
    /** 阶段4：追踪距离倍数（如 2.0） */
    private BigDecimal trailingDistanceMultiplier;
}

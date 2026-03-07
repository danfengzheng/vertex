package com.vertex.model.entity.strategy;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.vertex.common.core.base.BaseEntity;
import com.vertex.model.entity.quote.KLineInterval;
import com.vertex.model.entity.trading.ExecutionMode;
import com.vertex.model.entity.trading.MarginType;
import com.vertex.model.entity.trading.PositionSizing;
import com.vertex.model.entity.trading.TradeMode;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 策略配置实体
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("stg_strategy")
public class Strategy extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 策略名称 */
    private String name;

    /** 策略描述 */
    private String description;

    /** 交易所 */
    private String exchange;

    /** 交易对 */
    private String symbol;

    /** K线周期 */
    @TableField("`interval`")
    private KLineInterval interval;

    /** 指标配置 JSON (List<StrategyIndicatorConfig>) */
    private String indicatorConfigs;

    /** 是否启用 0-禁用 1-启用 */
    private Integer enabled;

    // ─── 交易配置 ───────────────────────────────────

    /** 是否开启自动交易 0-否 1-是 */
    private Integer autoTrade;

    /**
     * 自动交易最低信号强度门槛（0-100）。
     * 信号强度低于此值时，即使 autoTrade=1 也不触发实盘委托。
     * 未配置时默认使用 60，防止弱信号入场。
     */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
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
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private BigDecimal stopLossPct;

    /** 止盈百分比 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private BigDecimal takeProfitPct;

    /** 手续费率（如 0.001 = 0.1%），与回测对齐 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private BigDecimal feeRate;

    // ─── 合约配置（账户为 USDM/COINM 时生效） ─────────

    /** 杠杆倍数（1-125，默认 1） */
    private Integer leverage;

    /** 保证金模式 ISOLATED/CROSS */
    private MarginType marginType;

    // ─── 止损止盈配置 ────────────────────────────────────────────

    /** ATR 止损倍数（如 2.0），设置后优先于固定止损百分比 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private BigDecimal atrStopMultiplier;

    /** ATR 止盈倍数（如 3.0），设置后优先于固定止盈百分比 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private BigDecimal atrTakeProfitMultiplier;

    // ─── 移动ATR止损配置（四参数联动，任一不为null时启用移动止损） ──────

    /** 阶段1：初始止损倍数（如 3.5），开仓时 stopLoss = entry ∓ ATR × 此值 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private BigDecimal initialStopMultiplier;

    /**
     * 阶段2：激活保本的ATR距离倍数（如 1.8）。
     * 价格超过 entry + ATR × 此值 时，止损上移至入场价（保本）。
     */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private BigDecimal breakevenActivationMultiplier;

    /**
     * 阶段3：激活追踪的ATR距离倍数（如 2.5）。
     * 价格超过 entry + ATR × 此值 时，开始追踪止损（只升不降）。
     */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private BigDecimal trailingActivationMultiplier;

    /** 阶段4：追踪距离倍数（如 2.0），止损 = 最优价格 ∓ ATR × 此值 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private BigDecimal trailingDistanceMultiplier;

    /**
     * ATR止损专用K线周期（可选）。
     * 未设置时默认使用 strategy.interval。
     * 当主周期为 1m 但指标全用高周期时，可在此单独指定 ATR 计算周期（如 15m）。
     */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private KLineInterval atrInterval;
}

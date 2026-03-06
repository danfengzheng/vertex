package com.vertex.model.entity.strategy;

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

    /** 手续费率（如 0.001 = 0.1%），与回测对齐 */
    private BigDecimal feeRate;

    // ─── 合约配置（账户为 USDM/COINM 时生效） ─────────

    /** 杠杆倍数（1-125，默认 1） */
    private Integer leverage;

    /** 保证金模式 ISOLATED/CROSS */
    private MarginType marginType;

    // ─── 止损止盈配置 ────────────────────────────────────────────

    /** ATR 止损倍数（如 2.0），设置后优先于固定止损百分比 */
    private BigDecimal atrStopMultiplier;

    /** ATR 止盈倍数（如 3.0），设置后优先于固定止盈百分比 */
    private BigDecimal atrTakeProfitMultiplier;
}

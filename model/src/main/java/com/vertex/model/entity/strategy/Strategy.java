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

    /** 出场指标配置 JSON (List<StrategyIndicatorConfig>)，为空时不启用指标出场 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String exitIndicatorConfigs;

    /** 最大持仓K线根数，超过后强制平仓，null=不限 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Integer maxHoldingBars;

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

    // ─── 分阶段止盈配置（固定 3 档；启用后与 takeProfitPct / atrTakeProfitMultiplier 互斥） ──
    /**
     * 分阶段止盈：第 1 档触发价百分比（相对入场价，多头 +X% / 空头 -X%）。
     * size1 > 0 即视为启用分阶段止盈，此时 takeProfitPct 与 atrTakeProfitMultiplier 被忽略。
     */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private BigDecimal takeProfitPct1;

    /** 分阶段止盈：第 1 档平仓比例（占 initialQuantity 的百分比，0-100；为 0/null 即未启用分阶段）。 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private BigDecimal takeProfitSize1;

    /** 分阶段止盈：第 2 档触发价百分比；null/0 = 未配置该档（仅 TP1）。 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private BigDecimal takeProfitPct2;

    /** 分阶段止盈：第 2 档平仓比例；null/0 = 未配置该档。 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private BigDecimal takeProfitSize2;

    /** 分阶段止盈：第 3 档触发价百分比；null/0 = 未配置该档。 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private BigDecimal takeProfitPct3;

    /** 分阶段止盈：第 3 档平仓比例；null/0 = 未配置该档。 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private BigDecimal takeProfitSize3;

    /**
     * 分阶段止盈联动：触发指定档后将止损上移到入场价（保本退出）。
     * 取值 1/2/3 表示触发该档后保本，0 / null = 不启用。
     * 与移动 ATR 止损（initialStopMultiplier 等四参数）互斥，需在配置层校验。
     */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Integer moveStopToBreakevenAfterStage;

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
     * 峰值回撤止损百分比（如 5.0 = 5%）。
     * 开仓后持续追踪最优价格（多：最高价；空：最低价），
     * 当价格从峰值回撤超过此百分比时触发止损，不依赖 ATR。
     * 与移动ATR止损独立并存，先触发者出场。
     */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private BigDecimal trailingDropPct;

    /**
     * ATR止损专用K线周期（可选）。
     * 未设置时默认使用 strategy.interval。
     * 当主周期为 1m 但指标全用高周期时，可在此单独指定 ATR 计算周期（如 15m）。
     */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private KLineInterval atrInterval;

    /**
     * SuperTrend 动态止损偏移百分比（如 1.0 = 1%）。
     * <p>
     * 不为 null 且 > 0 时启用 SuperTrend 动态止损：
     * <ul>
     *   <li>持有多仓（LONG）：stopLoss = superTrend × (1 - offset%)</li>
     *   <li>持有空仓（SHORT）：stopLoss = superTrend × (1 + offset%)</li>
     * </ul>
     * 止损优先级：固定百分比止损 &gt; SuperTrend 动态止损 &gt; 峰值回撤止损。
     * </p>
     */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private BigDecimal superTrendSlOffsetPct;

    /**
     * 止损熔断开关（1=启用，0/null=关闭）。
     * 启用后，任意一笔止损触发且实际亏损时，暂停该策略开仓 24 小时。
     */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Integer pauseOnStopLoss;

    /**
     * 交易暂停截止时间（UTC）。
     * 由日亏损熔断自动写入，重启后仍有效。
     * 在此时间之前 executeSignal 不允许开新仓。
     * null = 未暂停。
     */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private java.time.LocalDateTime tradingPausedUntil;
}

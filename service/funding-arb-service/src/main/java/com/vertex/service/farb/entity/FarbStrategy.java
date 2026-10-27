package com.vertex.service.farb.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.vertex.common.core.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 资金费套利策略主表。
 * <p>字段与 sql/V38_funding_arb_module.sql 中的 {@code farb_strategy} 对齐。</p>
 *
 * @see com.vertex.service.farb.mapper.FarbStrategyMapper
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("farb_strategy")
public class FarbStrategy extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String name;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String description;

    // ─── 账户绑定 ───────────────────────────
    private Long spotAccountId;
    private Long perpAccountId;

    // ─── 状态机 ───────────────────────────
    /** STOPPED / RUNNING / PAUSED / HIBERNATE */
    private String status;
    /** PAPER / LIVE */
    private String executionMode;

    // ─── Symbol 过滤 ────────────────────────
    private Integer scanAllMarket;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String symbolWhitelist;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String symbolBlacklist;
    @TableField("min_24h_volume_usd")
    private BigDecimal min24hVolumeUsd;

    // ─── Regime 参数 ────────────────────────
    private Integer lookbackSettlements;
    private BigDecimal minAvgFundingBps;
    private BigDecimal minHitRatio;
    private BigDecimal minLatestFundingBps;
    private BigDecimal maxEntryBasisBps;
    private BigDecimal lsrMaxExtreme;
    private BigDecimal oiCrowdingThresholdPct;
    private BigDecimal volSpikeMultiplier;
    private Integer requireTakerConfirm;

    // ─── 出场触发参数 ─────────────────────
    private Integer exitRegimeBreakCount;
    private BigDecimal exitBasisDeltaBps;
    private BigDecimal exitTakeProfitPct;
    private Integer exitMaxHoldHours;
    private BigDecimal basisEmergencyBps;

    // ─── 仓位参数 ───────────────────────────
    private BigDecimal notionalPerPair;
    private Integer maxOpenPairs;
    private Integer maxPairsPerSymbol;
    private Integer perpLeverage;
    private String perpMarginType;

    // ─── 手续费参数 ─────────────────────────
    private Integer useRealtimeFee;
    private Integer useBnbDiscount;
    private BigDecimal spotFeeRate;
    private BigDecimal perpFeeRate;
    private BigDecimal minExpectedYieldPct;

    // ─── 风控参数 ───────────────────────────
    private BigDecimal maxDrawdownPct;
    private Integer fundingReversalStopDays;

    // ─── 预测反转（v2：软/硬两档退出）────────
    /** 是否启用预测反转 exit。0=关（v1 reactive），1=开 */
    private Integer predictReversalEnabled;
    /** 硬阈值：score < hard → 立即平仓（PREDICT_REVERSAL） */
    private BigDecimal predictHardScore;
    /** 软阈值：score < soft → 停开新仓 + 收紧 TP/regime break */
    private BigDecimal predictSoftScore;
    /** 软触发后 exit_regime_break_count 收紧到此值（比原值小） */
    private Integer predictBreakCountTightened;
    /** 软触发后 TP 收紧到此百分比（比原值小） */
    private BigDecimal predictTpTightenedPct;
    /** 软触发后 symbol 暂停开新仓的时长（分钟） */
    private Integer predictPauseNewMin;

    // ─── Dual-maker post-only 执行（fee 节省）───
    /** 是否启用双腿 post-only + 先成即市价对方腿（0=关，1=开）*/
    private Integer dualMakerEnabled;
    /** post-only 挂单最长等待时间（毫秒），超时都未成 → 全 MARKET 兜底 */
    private Long dualMakerTimeoutMs;
    /** fill 状态轮询间隔（毫秒） */
    private Long dualMakerPollMs;
    /** 现货 maker 手续费率（null → 用 spot_fee_rate，等价没启用 maker 优惠）*/
    private BigDecimal spotMakerFeeRate;
    /** 合约 maker 手续费率（null → 用 perp_fee_rate）*/
    private BigDecimal perpMakerFeeRate;

    // ─── 预结算负 rate 检测（提前平） ────────
    /** 是否启用：预估下次结算 rate < 0 时提前平（默认 1=开） */
    private Integer preSettleEnabled;
    /** 临近结算窗口（分钟）：此范围内只要预估 rate < 0 就平 */
    private Integer preSettleImminentMin;
    /** 中距结算窗口（分钟）：此范围内若预估 rate ≤ strongBps 就平 */
    private Integer preSettleStrongMin;
    /** 中距触发阈值（bps，负数）：strong 窗口内达到此值才触发 */
    private BigDecimal preSettleStrongBps;

    // ─── 强平保护（v2）──────────────────────
    /** 是否启用强平保护（默认 1 开启） */
    private Integer liquidationProtectionEnabled;
    /**
     * 保护平仓阈值（百分比，perp 反向价格波动）。为 null 时按 0.7 × (1/leverage) 自动计算。
     * <p>示例：2x 杠杆自动阈值 = 0.7 × 50% = 35%（价格涨 35% 时主动平仓）。</p>
     */
    private BigDecimal liquidationSafeMovePct;

    // ─── 运行时快照 ─────────────────────────
    private Integer currentOpenPairs;
    private BigDecimal totalFundingPnl;
    private BigDecimal totalBasisPnl;
    private BigDecimal totalFeePaid;
    private BigDecimal totalNetPnl;
    private Integer totalPairsOpened;
    private Integer totalPairsClosed;
}

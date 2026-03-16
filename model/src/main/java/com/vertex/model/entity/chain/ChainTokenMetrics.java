package com.vertex.model.entity.chain;

import com.baomidou.mybatisplus.annotation.*;
import com.vertex.common.core.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 链上代币指标快照实体
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("chn_token_metrics")
public class ChainTokenMetrics extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 关联 chn_token.id */
    private Long tokenId;

    /** 快照时间戳（毫秒） */
    private Long snapshotTime;

    // ─── 链上指标 ─────────────────────────────────
    /** 持有者数量 */
    private Integer holderCount;

    /** 最近1小时交易数 */
    @TableField(value = "tx_count_1h", updateStrategy = FieldStrategy.ALWAYS)
    private Integer txCount1h;

    /** 流动性添加次数 */
    private Integer lpAddCount;

    /** 流动性是否锁定：0/1 */
    private Integer liquidityLocked;

    /** 合约是否已验证（BSC）：0/1 */
    private Integer contractVerified;

    // ─── 市场指标 ─────────────────────────────────
    /** USD 价格 */
    private BigDecimal priceUsd;

    /** USD 市值 */
    private BigDecimal marketCapUsd;

    /** USD 流动性 */
    private BigDecimal liquidityUsd;

    /** 24h USD 成交量 */
    @TableField(value = "volume_24h_usd", updateStrategy = FieldStrategy.ALWAYS)
    private BigDecimal volume24hUsd;

    /** 1h 价格变化百分比 */
    @TableField(value = "price_change_1h_pct", updateStrategy = FieldStrategy.ALWAYS)
    private BigDecimal priceChange1hPct;

    /** 24h 价格变化百分比 */
    @TableField(value = "price_change_24h_pct", updateStrategy = FieldStrategy.ALWAYS)
    private BigDecimal priceChange24hPct;

    /** 1h 买入压力比例（买入/总交易） 0-100 */
    @TableField(value = "buy_pressure_1h", updateStrategy = FieldStrategy.ALWAYS)
    private BigDecimal buyPressure1h;

    // ─── 代币经济指标 ─────────────────────────────
    /** Top10 持仓集中度百分比 */
    @TableField(value = "top10_holder_pct", updateStrategy = FieldStrategy.ALWAYS)
    private BigDecimal top10HolderPct;

    /** 部署者持仓百分比 */
    private BigDecimal deployerHoldingPct;

    /** LP 池占总供应量百分比 */
    private BigDecimal lpPoolPct;

    // ─── 新颖度指标 ───────────────────────────────
    /** 距部署分钟数 */
    private Integer ageMinutes;

    /** 是否在 Pump.fun 上市：0/1 */
    private Integer pumpFunListed;

    // ─── 一级市场专属指标 ─────────────────────────
    /** Bonding curve 填充进度（0-100%）；null 表示已上 DEX（二级市场） */
    private BigDecimal bondingCurveProgress;

    /** 社区讨论数（Pump.fun reply_count / Four.meme commentCount） */
    private Integer replyCount;

    /** 发射台名称：pump.fun / four.meme / null=已上DEX */
    private String launchpadName;
}

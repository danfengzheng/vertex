package com.vertex.service.chain.source;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 从链上数据源采集的新代币原始数据
 * <p>
 * 由各链 ChainDataSource 实现填充，供评分引擎和持久化层使用。
 * 部分字段可能为 null（数据源未提供），评分时需做空值保护。
 */
@Data
@Builder
public class NewTokenRawData {

    // ── 基础信息 ───────────────────────────────────────────
    /** 所属链标识，e.g. "BNB", "SOL" */
    private String chain;
    /** 合约 / Mint 地址 */
    private String contractAddress;
    /** 代币符号 */
    private String symbol;
    /** 代币名称 */
    private String name;
    /** 精度 */
    private Integer decimals;
    /** 部署者地址 */
    private String deployerAddress;
    /** 交易对地址（Pool / Market） */
    private String pairAddress;
    /** 代币上线时间（Unix 毫秒） */
    private Long listingTimeMs;

    // ── 链上指标 ─────────────────────────────────────────
    /** 持有者数量 */
    private Integer holderCount;
    /** 最近 1h 交易笔数 */
    private Integer txCount1h;
    /** LP 添加次数（流动性注入历史） */
    private Integer lpAddCount;
    /** 流动性是否锁定 */
    private Boolean liquidityLocked;
    /** 合约是否已验证 */
    private Boolean contractVerified;

    // ── 市场指标 ─────────────────────────────────────────
    /** 当前价格（USD） */
    private BigDecimal priceUsd;
    /** 市值（USD） */
    private BigDecimal marketCapUsd;
    /** 流动性（USD） */
    private BigDecimal liquidityUsd;
    /** 24h 成交量（USD） */
    private BigDecimal volume24hUsd;
    /** 1h 价格变化百分比 */
    private BigDecimal priceChange1hPct;
    /** 24h 价格变化百分比 */
    private BigDecimal priceChange24hPct;
    /** 1h 内买盘压力（buyVolume / totalVolume），0~1 */
    private BigDecimal buyPressure1h;

    // ── 代币经济指标 ──────────────────────────────────────
    /** Top 10 持有者占比（0~100） */
    private BigDecimal top10HolderPct;
    /** 部署者当前持仓占比（0~100） */
    private BigDecimal deployerHoldingPct;
    /** LP 池占总供应量的比例（0~100） */
    private BigDecimal lpPoolPct;

    // ── 新颖度指标 ────────────────────────────────────────
    /** 代币存在时长（分钟） */
    private Integer ageMinutes;
    /** 是否通过 Pump.fun 上市 */
    private Boolean pumpFunListed;

    // ── 一级市场专属指标 ──────────────────────────────────
    /**
     * Bonding curve 填充进度（0-100%）。
     * 非 null 表示代币尚在一级市场（bonding curve 阶段），尚未毕业到 DEX。
     * null 表示已上 DEX（二级市场），流动性等指标通过 DEX 数据填充。
     */
    private Double bondingCurveProgress;
    /**
     * 社区讨论数（Pump.fun reply_count / Four.meme comment count）。
     * 衡量社区活跃度，是一级市场代币的核心热度信号。
     */
    private Integer replyCount;
    /** 平台名称：pump.fun / four.meme / other */
    private String launchpadName;
}

package com.vertex.model.vo.chain;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 链上新币详情 VO（含最新指标快照）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class ChainTokenDetailVO extends ChainTokenVO {

    private Integer decimals;
    private String totalSupply;
    private String deployerAddress;
    private Long deployBlock;
    private String pairAddress;
    private String quoteToken;

    // 最新指标快照
    private BigDecimal priceChange1hPct;
    private BigDecimal priceChange24hPct;
    private BigDecimal buyPressure1h;
    private BigDecimal top10HolderPct;
    private BigDecimal deployerHoldingPct;
    private BigDecimal lpPoolPct;
    private Integer ageMinutes;
    private Integer pumpFunListed;
    private Integer liquidityLocked;
    private Integer contractVerified;
    private Integer txCount1h;
    private Integer lpAddCount;

    // 一级市场专属
    private BigDecimal bondingCurveProgress;
    private Integer replyCount;
    private String launchpadName;
}

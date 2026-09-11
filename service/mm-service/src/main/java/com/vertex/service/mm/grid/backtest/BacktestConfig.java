package com.vertex.service.mm.grid.backtest;

import lombok.Data;

import java.math.BigDecimal;

/**
 * Grid 回测请求配置。
 */
@Data
public class BacktestConfig {
    /** 起始时间戳 ms */
    private Long startTime;
    /** 结束时间戳 ms */
    private Long endTime;
    /** 主 K 线周期（默认 5m）*/
    private String klineInterval = "5m";
    /** 初始本金（用于计算收益率）*/
    private BigDecimal initialCapital = new BigDecimal("3000");
    /** 假设 maker 手续费率（默认 0，币安 USDC-M 0 fee promo）*/
    private BigDecimal feeRate = BigDecimal.ZERO;
    /** 是否模拟 5 层保护（true = 完整；false = 只做纯网格套利）*/
    private boolean simulateProtection = true;
}

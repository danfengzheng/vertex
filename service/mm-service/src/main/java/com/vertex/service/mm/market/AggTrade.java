package com.vertex.service.mm.market;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 一笔聚合成交（币安 aggTrade 流事件）。
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AggTrade {
    private String symbol;
    private BigDecimal price;
    private BigDecimal qty;
    /** true = maker 是卖方（即 taker 是主动买入 → 拉升成交）；
     *  false = maker 是买方（taker 主动卖出 → 打压成交）*/
    private boolean makerIsSeller;
    /** 事件时间 ms */
    private long timeMs;
}

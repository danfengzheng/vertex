package com.vertex.model.entity.quote;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 统一 K 线数据模型（与交易所无关）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KLine implements Serializable {

    /** 交易对，统一格式如 BTC-USDT */
    private String symbol;

    /** 交易所来源，如 binance、okx */
    private String exchange;

    /** K线周期 */
    private KLineInterval interval;

    /** 开盘时间戳（毫秒） */
    private Long openTime;

    /** 收盘时间戳（毫秒） */
    private Long closeTime;

    /** 开盘价 */
    private BigDecimal open;

    /** 最高价 */
    private BigDecimal high;

    /** 最低价 */
    private BigDecimal low;

    /** 收盘价 */
    private BigDecimal close;

    /** 成交量（base asset） */
    private BigDecimal volume;

    /** 成交额（quote asset） */
    private BigDecimal quoteVolume;

    /** 成交笔数 */
    private Integer trades;

    /** 此K线是否已完结 */
    private Boolean closed;
}

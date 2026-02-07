package com.vertex.model.vo.quote;

import com.vertex.model.entity.quote.KLineInterval;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * K线数据 VO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KLineVO implements Serializable {

    private String symbol;
    private String exchange;
    private KLineInterval interval;
    private Long openTime;
    private Long closeTime;
    private BigDecimal open;
    private BigDecimal high;
    private BigDecimal low;
    private BigDecimal close;
    private BigDecimal volume;
    private BigDecimal quoteVolume;
    private Integer trades;
    private Boolean closed;
}

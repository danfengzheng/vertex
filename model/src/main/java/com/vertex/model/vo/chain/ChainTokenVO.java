package com.vertex.model.vo.chain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 链上新币列表展示 VO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChainTokenVO implements Serializable {

    private Long id;
    private String chain;
    private String contractAddress;
    private String symbol;
    private String name;
    private Long deployTime;
    private Integer score;
    private Integer scoreOnchain;
    private Integer scoreMarket;
    private Integer scoreTokenomics;
    private Integer scoreNovelty;
    private String status;
    private Integer alerted;
    private BigDecimal priceUsd;
    private BigDecimal marketCapUsd;
    private BigDecimal liquidityUsd;
    private BigDecimal volume24hUsd;
    private Integer holderCount;
    private LocalDateTime createTime;
    /** 数据来源标识：bnb_primary / bnb_alpha / bnb_trending 等 */
    private String dataSource;
}

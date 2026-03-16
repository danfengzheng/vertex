package com.vertex.model.vo.chain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 链上数据源配置 VO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChainSourceConfigVO implements Serializable {

    private Long id;

    /** 数据源标识：bnb_alpha | bnb_trending */
    private String sourceId;

    /** 数据源显示名称 */
    private String sourceName;

    /** 是否启用：0-禁用 1-启用 */
    private Integer enabled;

    /** 最低市值 USD */
    private Double minMarketCapUsd;

    /** 最高市值 USD（null=不限，仅 bnb_trending 使用） */
    private Double maxMarketCapUsd;

    /** 最低流动性 USD */
    private Double minLiquidityUsd;

    /** 最低成交量/流动性换手率（仅 bnb_trending 使用） */
    private Double minVolumeLiquidityRatio;

    /** 1h 价格最低涨幅（仅 bnb_trending 使用） */
    private Double minPriceChange1hPct;

    /** 每次 API 拉取数量（仅 bnb_alpha 使用） */
    private Integer pageSize;

    /** 每次扫描最多处理代币数（仅 bnb_trending 使用） */
    private Integer scanLimit;
}

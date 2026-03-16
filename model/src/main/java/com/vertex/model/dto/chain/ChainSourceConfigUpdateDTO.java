package com.vertex.model.dto.chain;

import lombok.Data;

import jakarta.validation.constraints.NotNull;

/**
 * 更新链上数据源配置 DTO
 */
@Data
public class ChainSourceConfigUpdateDTO {

    @NotNull(message = "id 不能为空")
    private Long id;

    /** 是否启用：0-禁用 1-启用 */
    @NotNull(message = "enabled 不能为空")
    private Integer enabled;

    /** 最低市值 USD */
    private Double minMarketCapUsd;

    /** 最高市值 USD（null=不限） */
    private Double maxMarketCapUsd;

    /** 最低流动性 USD */
    private Double minLiquidityUsd;

    /** 最低成交量/流动性换手率 */
    private Double minVolumeLiquidityRatio;

    /** 1h 价格最低涨幅（可为负数，表示允许微跌） */
    private Double minPriceChange1hPct;

    /** 每次 API 拉取数量 */
    private Integer pageSize;

    /** 每次扫描最多处理代币数 */
    private Integer scanLimit;
}

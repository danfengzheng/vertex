package com.vertex.model.entity.trading;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.vertex.common.core.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 平台通用币对表
 * <p>
 * 存储与交易所无关的标准化标识（如 ETHUSDT），策略/信号/持仓均引用此值。
 * 实际下单或订阅行情时，通过 {@link TrdExchangeSymbol} 做交易所专用格式映射。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("trd_symbol")
public class TrdSymbol extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 平台通用标识，如 ETHUSDT */
    private String symbol;

    /** 标的资产，如 ETH */
    private String baseAsset;

    /** 计价资产，如 USDT */
    private String quoteAsset;
}

package com.vertex.model.vo.trading;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 交易所账户单个资产余额
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AssetBalanceVO implements Serializable {

    /** 资产名称（如 USDT、BTC、ETH） */
    private String asset;

    /** 可用余额 */
    private BigDecimal free;

    /** 冻结余额（挂单占用） */
    private BigDecimal locked;

    /** 总余额 = free + locked */
    private BigDecimal total;
}

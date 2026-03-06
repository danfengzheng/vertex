package com.vertex.model.entity.trading;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.vertex.common.core.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 交易所账户实体
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("trd_exchange_account")
public class ExchangeAccount extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 账户名称 */
    private String name;

    /** 交易所 */
    private String exchange;

    /** API Key（AES-GCM 加密存储） */
    private String apiKey;

    /** API Secret（AES-GCM 加密存储） */
    private String apiSecret;

    /** 状态 0-禁用 1-正常 */
    private Integer status;

    /** 市场类型 SPOT/USDM/COINM */
    private MarketType marketType;
}

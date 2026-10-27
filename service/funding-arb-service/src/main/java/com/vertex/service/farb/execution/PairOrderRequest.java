package com.vertex.service.farb.execution;

import com.vertex.service.farb.entity.FarbStrategy;
import com.vertex.service.farb.regime.ArbCandidate;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 执行请求：策略 + 候选 + 计算好的下单参数。
 */
@Data
@Builder
public class PairOrderRequest {

    private FarbStrategy strategy;
    private ArbCandidate candidate;

    /** 现货 BUY 数量（已 align stepSize） */
    private BigDecimal spotQty;

    /** 期货 SELL 数量（已 align stepSize，通常 = spotQty） */
    private BigDecimal perpQty;

    /** 名义金额 USD（估算，仅记账用）*/
    private BigDecimal notionalUsd;

    /** 现货预估成交价（当前 ask，用于回退价保护）*/
    private BigDecimal spotEstimatedPrice;

    /** 期货预估成交价（当前 bid） */
    private BigDecimal perpEstimatedPrice;

    /** IOC LIMIT 的价格上限（BUY = ask × 1.001），无限价保护则 null */
    private BigDecimal spotIocLimitPrice;

    /** IOC LIMIT 价格下限（SELL = bid × 0.999） */
    private BigDecimal perpIocLimitPrice;

    /** clientOrderId 前缀（用于对账/幂等），默认 farb-{strategy}-{ts}-{leg} */
    private String clientOrderPrefix;
}

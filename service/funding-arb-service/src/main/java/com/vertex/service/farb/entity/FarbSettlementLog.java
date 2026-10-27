package com.vertex.service.farb.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 每次 funding 结算记账。累计每 pair 的 funding_pnl。
 */
@Data
@TableName("farb_settlement_log")
public class FarbSettlementLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long strategyId;
    private Long positionId;
    private String symbol;

    private LocalDateTime settlementTime;
    private BigDecimal fundingRate;
    private Integer fundingIntervalHours;
    private BigDecimal markPriceAtSettle;
    private BigDecimal perpQty;
    private BigDecimal fundingAmount;

    /** USER_DATA_STREAM / REST_FALLBACK / PAPER_MOCK / LOCAL_ESTIMATE */
    private String source;
    private String binanceIncomeId;

    private LocalDateTime createTime;
}

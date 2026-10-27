package com.vertex.service.farb.execution;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 单腿下单结果 —— saga 补偿判定用。
 */
@Data
@Builder
public class PairOrderResult {

    private boolean success;
    private String orderId;
    private String clientOrderId;
    /** 实际成交量（IOC 可能部分成交）*/
    private BigDecimal executedQty;
    /** 平均成交价 */
    private BigDecimal avgFillPrice;
    /** 实际扣除的手续费（USD 单位 = qty × price × feeRate） */
    private BigDecimal feePaid;
    /** 失败原因（success=false 时填）*/
    private String errorMessage;
    private String errorCode;
}

package com.vertex.service.mm.binance;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 挂单响应。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MmOrderResult {

    /** true=成功挂上，false=GTX 被拒或其他错误 */
    private boolean success;
    private Long exchangeOrderId;
    private String clientOrderId;
    private String status;         // NEW / PARTIALLY_FILLED / FILLED / REJECTED / EXPIRED
    private BigDecimal executedQty;
    private BigDecimal avgPrice;
    /** 错误码（例如 -2010=GTX rejected） */
    private Integer errorCode;
    private String errorMessage;
    /** true=因为 GTX post-only 冲突被拒（下次可重试或换价） */
    private boolean gtxRejected;
}

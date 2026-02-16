package com.vertex.model.vo.trading;

import com.vertex.model.entity.trading.ExecutionMode;
import com.vertex.model.entity.trading.PositionSide;
import com.vertex.model.entity.trading.PositionStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 持仓 VO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PositionVO implements Serializable {

    private Long id;
    private Long strategyId;
    private String strategyName;
    private Long accountId;
    private String accountName;
    private String exchange;
    private String symbol;
    private PositionSide side;
    private BigDecimal quantity;
    private BigDecimal entryPrice;
    private BigDecimal currentPrice;
    private BigDecimal unrealizedPnl;
    private BigDecimal realizedPnl;
    private BigDecimal stopLoss;
    private BigDecimal takeProfit;
    private PositionStatus status;
    private ExecutionMode tradeMode;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}

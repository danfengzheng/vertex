package com.vertex.model.vo.trading;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 按策略维度的已平仓盈亏项
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StrategyPnlItem implements Serializable {

    private Long strategyId;
    private String strategyName;
    /** 已平仓笔数 */
    private Integer closedCount;
    /** 已实现盈亏汇总 */
    private BigDecimal realizedPnl;
}

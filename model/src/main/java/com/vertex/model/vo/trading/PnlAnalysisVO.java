package com.vertex.model.vo.trading;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;

/**
 * 已平仓盈亏分析结果
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PnlAnalysisVO implements Serializable {

    /** 总已实现盈亏（所有已平仓） */
    private BigDecimal totalPnl;

    /** 模拟盘已实现盈亏 */
    private BigDecimal paperPnl;

    /** 实盘已实现盈亏 */
    private BigDecimal livePnl;

    /** 按策略汇总 */
    private List<StrategyPnlItem> byStrategy;

    /** 按交易对汇总 */
    private List<SymbolPnlItem> bySymbol;
}

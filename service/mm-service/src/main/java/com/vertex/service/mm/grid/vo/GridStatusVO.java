package com.vertex.service.mm.grid.vo;

import com.vertex.service.mm.grid.entity.GridStrategy;
import com.vertex.service.mm.risk.DepletionState;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 网格策略详情 VO —— 前端 dashboard 用。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GridStatusVO {

    private GridStrategy strategy;

    // ── 以下字段是"实时权威值"，从 GridState 内存直接取，不受 DB 快照延迟影响 ──
    /** 当前中枢价（AdaptiveScheduler 5min 才写 DB，这里给最新内存值）*/
    private java.math.BigDecimal realtimeCenterPrice;
    /** 当前步长 %（同上）*/
    private java.math.BigDecimal realtimeStepPct;
    /** 当前 LONG 持仓（ACCOUNT_UPDATE 每次刷新内存，不写 DB）*/
    private java.math.BigDecimal realtimeLongQty;
    /** 当前 SHORT 持仓 */
    private java.math.BigDecimal realtimeShortQty;

    /** 当前挂单数（含 LONG + SHORT）*/
    private Integer activeOrders;
    private Integer longActiveOrders;
    private Integer shortActiveOrders;

    /** 当前保护层级 */
    private String currentLayer;

    /** 窗口耗尽 + 综合评分状态 */
    private DepletionState depletion;

    /** 当前 mark price（从 MarketQuoteCache）*/
    private java.math.BigDecimal markPrice;
    private java.math.BigDecimal spread;
    private java.math.BigDecimal spreadPct;
}

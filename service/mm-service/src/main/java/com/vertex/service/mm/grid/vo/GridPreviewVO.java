package com.vertex.service.mm.grid.vo;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 网格预览返回结构。
 * <p>结构分区：基础回显 → 价格分布 → 多头满仓 → 空头满仓 → 汇总 → 单轮利润 → 警告。</p>
 */
@Data
@Builder
public class GridPreviewVO {

    private Basics basics;
    private Distribution distribution;
    private WindowCoverage windowCoverage;
    private SideProjection longSide;
    private SideProjection shortSide;
    private Totals totals;
    private RoundProfit roundProfit;
    private List<String> warnings;

    // ─────────────────────────────────────────────────────────────
    // 子结构
    // ─────────────────────────────────────────────────────────────

    @Data
    @Builder
    public static class Basics {
        private String symbol;
        private String side;
        private String mode;
        private BigDecimal lowerPrice;
        private BigDecimal upperPrice;
        private Integer gridCount;
        private BigDecimal qtyPerGrid;
        private BigDecimal currentPrice;
        private Integer leverage;
        private BigDecimal feeRate;
    }

    @Data
    @Builder
    public static class Distribution {
        /** 前 5 格价格（便于前端预览显示） */
        private List<BigDecimal> stepPricesFirstFew;
        /** 后 5 格价格 */
        private List<BigDecimal> stepPricesLastFew;
        /** 平均绝对步长（USDT） */
        private BigDecimal avgStepAbsolute;
        /** 步长百分比（等比恒定 / 等差均值）*/
        private BigDecimal stepPct;
        /** 单格净利润率（扣双向手续费）*/
        private BigDecimal netProfitPerGridPct;
    }

    @Data
    @Builder
    public static class WindowCoverage {
        /** 每档绝对步长（USDT） */
        private BigDecimal stepAbsolute;
        /** 每档百分比步长 */
        private BigDecimal stepPct;
        /** 单侧窗口档数（windowSizePerSide）*/
        private Integer windowSize;
        /** 单侧覆盖价（USDT） = stepAbsolute × windowSize */
        private BigDecimal sideCoverageAbsolute;
        /** 单侧覆盖百分比 = stepPct × windowSize */
        private BigDecimal sideCoveragePct;
        /** 当窗口不够时提示 */
        private String note;
    }

    @Data
    @Builder
    public static class SideProjection {
        /** 该方向可用槽位数（等于会挂的最大格数）*/
        private Integer slots;
        /** 全部成交后累计数量 */
        private BigDecimal maxQty;
        /** 全部成交后累计名义价值（USDT） */
        private BigDecimal maxNotional;
        /** 全部成交后均价 */
        private BigDecimal avgPrice;
        /** 保证金需求（= maxNotional / leverage）*/
        private BigDecimal requiredMargin;
        /** 预估强平价 */
        private BigDecimal estLiquidationPrice;
    }

    @Data
    @Builder
    public static class Totals {
        /** 多空保证金合计 */
        private BigDecimal totalRequiredMargin;
        /** 名义价值合计 */
        private BigDecimal maxAggregateNotional;
    }

    @Data
    @Builder
    public static class RoundProfit {
        /** 毛利润率 %（同 distribution.stepPct）*/
        private BigDecimal grossPct;
        /** 净利润率 %（扣双向手续费）*/
        private BigDecimal netPct;
        /** 单轮净利润 USDT（按 qtyPerGrid × 均价 × netPct 估算）*/
        private BigDecimal netUsdtPerRound;
    }
}

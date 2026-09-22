package com.vertex.service.mm.grid.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 网格策略预览请求。
 * <p>
 * 用于配置阶段的实时估算，不落库。前端表单参数变化 → debounce 300ms → 调此接口。
 * </p>
 */
@Data
public class GridPreviewRequest {

    /** 交易对，如 ETHUSDT（仅用于回显，不参与计算） */
    private String symbol;

    /** LONG_ONLY / SHORT_ONLY / BOTH */
    private String side;

    /** ARITHMETIC / GEOMETRIC */
    private String mode;

    /** 区间下限 */
    private BigDecimal lowerPrice;

    /** 区间上限 */
    private BigDecimal upperPrice;

    /** 网格数量（价位数 = count + 1） */
    private Integer gridCount;

    /** 每格数量（合约张数或币量） */
    private BigDecimal qtyPerGrid;

    /** 当前价（用于计算 buySlots / sellSlots 与保证金） */
    private BigDecimal currentPrice;

    /** 杠杆 */
    private Integer leverage;

    /** 每侧窗口档数（不传时预览面板只显示步长，不计算覆盖率）*/
    private Integer windowSizePerSide;

    /** 手续费率（如 0.0005 表示 0.05%），null 时后端使用默认 0.0005 */
    private BigDecimal feeRate;

    /** 维持保证金率（隔离仓强平价估算用），null 时后端使用默认 0.005 */
    private BigDecimal maintenanceMarginRate;
}

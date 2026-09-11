package com.vertex.service.mm.grid.service;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 网格线上的一个 slot —— 记录该价位当前挂着的挂单（可能 buy+sell 各一）。
 * <p>
 * hedge 模式下同价位可能同时有 LONG 侧和 SHORT 侧的挂单，用 positionSide 区分。
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GridSlot {
    private BigDecimal price;
    private int gridIndex;                // 中枢价索引：0 = center，±1 = 第一档...

    /** LONG 侧挂单（BUY 或 SELL）*/
    private Long longSideOrderId;         // grid_order.id
    private String longSideClientId;

    /** SHORT 侧挂单 */
    private Long shortSideOrderId;
    private String shortSideClientId;

    private LocalDateTime lastActivityAt;
}

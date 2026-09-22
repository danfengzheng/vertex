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

    /**
     * V1 网格占用不变量（DESIGN_V1_SIMPLE §1.4）：
     * OPEN 单成交后至配对 CLOSE 单成交前，该 slot 在对应 positionSide 上视为 POSITION_HELD，
     * 滑动窗口不得在此 slot 重新挂新的 OPEN 单，避免重复开仓累积超额头寸。
     */
    @lombok.Builder.Default
    private boolean longPositionHeld = false;
    @lombok.Builder.Default
    private boolean shortPositionHeld = false;

    private LocalDateTime lastActivityAt;

    // ─── 三态机（DESIGN_V1_SIMPLE §1.4）─────────────────────
    public SlotState longState() {
        if (longPositionHeld) return SlotState.POSITION_HELD;
        if (longSideOrderId != null) return SlotState.OPEN_ORDER_LIVE;
        return SlotState.EMPTY;
    }

    public SlotState shortState() {
        if (shortPositionHeld) return SlotState.POSITION_HELD;
        if (shortSideOrderId != null) return SlotState.OPEN_ORDER_LIVE;
        return SlotState.EMPTY;
    }

    public SlotState stateOf(String positionSide) {
        return "SHORT".equalsIgnoreCase(positionSide) ? shortState() : longState();
    }

    public enum SlotState {
        /** 无活单也无未平头寸 —— 允许挂开仓单 */
        EMPTY,
        /** 已挂 OPEN 单等待成交 —— 无需重复挂 */
        OPEN_ORDER_LIVE,
        /** OPEN 已成交、CLOSE 单在别处挂着 —— 禁止再挂 OPEN */
        POSITION_HELD
    }
}

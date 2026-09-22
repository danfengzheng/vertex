package com.vertex.service.mm.grid.service;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.NavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 单个网格策略的运行时状态（内存）。
 * <p>
 * ConcurrentSkipListMap 支持并发访问 + 有序遍历。
 * 分 upperSlots（价格 > center，升序）和 lowerSlots（价格 < center，降序）。
 * </p>
 */
@Data
public class GridState {

    private final long strategyId;
    private final String symbol;

    /** 中枢价（volatile，SlidingWindowManager 唯一写入） */
    private volatile BigDecimal centerPrice;
    /** 当前步长（%）*/
    private volatile BigDecimal stepPct;
    private volatile int windowSizePerSide;

    /**
     * V1 浮动网格：等差模式的绝对步长（USDT），从 (upper-lower)/count 精确计算。
     * <p>非 null 时表示 V1 ARITHMETIC 模式，slot 价用 center ± i × absoluteStep。
     * GEOMETRIC 模式此字段保持 null，slot 价用 center × (1 ± stepFrac × i) 逼近。
     * V2 模式（未填 V1 几何字段）也保持 null。</p>
     */
    private volatile BigDecimal absoluteStep;

    /** V1 模式记录 gridMode（ARITHMETIC / GEOMETRIC），供 slot 价计算分派 */
    private volatile String gridMode;

    /** BUY grid 挂单（价格降序：靠近中枢的在最上）*/
    private final NavigableMap<BigDecimal, GridSlot> lowerSlots =
            new ConcurrentSkipListMap<>(Comparator.reverseOrder());

    /** SELL grid 挂单（价格升序：靠近中枢的在最上）*/
    private final NavigableMap<BigDecimal, GridSlot> upperSlots =
            new ConcurrentSkipListMap<>();

    /** 分侧开关（Layer 2/3 分侧控制）*/
    private volatile boolean longSideEnabled = true;
    private volatile boolean shortSideEnabled = true;

    /** 当前生效的保护层级（NORMAL/LAYER1/LAYER2/LAYER3/LAYER4）*/
    private final AtomicReference<String> currentLayer = new AtomicReference<>("NORMAL");

    /** 上次 reshuffle 时间（防频繁触发）*/
    private volatile LocalDateTime lastReshuffleAt;

    /** 上次 adaptive step 调整时间 */
    private volatile LocalDateTime lastAdaptiveAt;

    /** 上次 layer 变化时间（用于最小保持时长）*/
    private volatile LocalDateTime lastLayerChangeAt;

    /** 当前 LONG 持仓量（从 UserDataStream ACCOUNT_UPDATE 实时更新，权威内存值）*/
    private volatile BigDecimal currentLongQty = BigDecimal.ZERO;
    /** 当前 SHORT 持仓量 */
    private volatile BigDecimal currentShortQty = BigDecimal.ZERO;

    public GridState(long strategyId, String symbol) {
        this.strategyId = strategyId;
        this.symbol = symbol;
    }

    // ─── 辅助：统计当前活跃挂单数 ────────────────────────

    /** 当前 LONG 侧活跃开仓单（BUY grid）数 —— 排除 sentinel（PLACE 意图已生成但 orderId 未回填）*/
    public int countLongOpenOrders() {
        int n = 0;
        for (GridSlot s : lowerSlots.values()) {
            Long id = s.getLongSideOrderId();
            if (id != null && id != SlidingWindowManager.PENDING_PLACE_SENTINEL) n++;
        }
        return n;
    }

    /** 当前 SHORT 侧活跃开仓单（SELL grid）数 —— 同上，排除 sentinel */
    public int countShortOpenOrders() {
        int n = 0;
        for (GridSlot s : upperSlots.values()) {
            Long id = s.getShortSideOrderId();
            if (id != null && id != SlidingWindowManager.PENDING_PLACE_SENTINEL) n++;
        }
        return n;
    }

    /** 总活跃挂单 */
    public int countAllActive() {
        return countLongOpenOrders() + countShortOpenOrders();
    }
}

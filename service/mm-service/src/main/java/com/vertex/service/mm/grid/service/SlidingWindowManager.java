package com.vertex.service.mm.grid.service;

import com.vertex.service.mm.binance.SymbolFiltersCache;
import com.vertex.service.mm.grid.entity.GridStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 挂单矩阵状态机 —— mm-service 网格核心。
 * <p>
 * 每个 grid_strategy 一个 {@link GridState} 实例。管理挂单的：
 * <ul>
 *   <li>冷启动：initialize() 一次挂满 window</li>
 *   <li>成交响应：onOrderFilled() 补挂配对单 + 判断是否 reshuffle</li>
 *   <li>滑动：shiftWindow() 渐进边缘迁移</li>
 *   <li>步长调整：adjustStep() 保守渐进</li>
 *   <li>分侧控制：pauseSide() / resumeSide()</li>
 * </ul>
 * </p>
 * <p>
 * <b>本类只维护状态和生成"意图"</b>（要挂什么单、要撤什么单）。
 * 实际下单动作由 GridEngine 调 MmOrderClient 执行。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SlidingWindowManager {

    private final SymbolFiltersCache filtersCache;

    /** 所有活跃策略状态 —— strategyId → GridState */
    private final ConcurrentMap<Long, GridState> states = new ConcurrentHashMap<>();

    /** V1 防御性锁：CLOSE 密集时序化 refillEmptySlots，避免同 slot 双挂 */
    private final ConcurrentMap<Long, java.util.concurrent.locks.ReentrantLock> refillLocks
            = new ConcurrentHashMap<>();

    // ─── 状态管理 ────────────────────────────────────────────

    public GridState getOrCreateState(GridStrategy strategy) {
        return states.computeIfAbsent(strategy.getId(),
                k -> new GridState(strategy.getId(), strategy.getSymbol()));
    }

    public GridState getState(long strategyId) {
        return states.get(strategyId);
    }

    public void removeState(long strategyId) {
        states.remove(strategyId);
        refillLocks.remove(strategyId);  // 防内存泄漏：策略下线时清锁
    }

    // ─── 冷启动 ──────────────────────────────────────────────

    /**
     * 生成初始挂单意图。GridEngine 拿去下单。
     *
     * @param strategy    策略配置
     * @param centerPrice 初始中枢价
     * @param stepPct     初始步长（%）
     * @return 需要挂的 GridOrderIntent 列表
     */
    public List<GridOrderIntent> initialize(GridStrategy strategy, BigDecimal centerPrice, BigDecimal stepPct) {
        GridState state = getOrCreateState(strategy);
        state.setCenterPrice(centerPrice);
        state.setStepPct(stepPct);
        state.setWindowSizePerSide(strategy.getWindowSizePerSide());
        state.setLongSideEnabled(strategy.getLongSideEnabled() == null || strategy.getLongSideEnabled() == 1);
        state.setShortSideEnabled(strategy.getShortSideEnabled() == null || strategy.getShortSideEnabled() == 1);

        // V1 浮动网格：ARITHMETIC 时算出精确 absStep 存到 state；GEOMETRIC 保持 null 用 stepFrac 逼近
        state.setGridMode(strategy.getGridMode());
        state.setAbsoluteStep(computeAbsoluteStep(strategy));

        // 清空之前的 slot 记录（防重复启动）
        state.getLowerSlots().clear();
        state.getUpperSlots().clear();

        List<GridOrderIntent> intents = new ArrayList<>();
        int size = state.getWindowSizePerSide();
        BigDecimal stepFrac = stepPct.divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP);

        // 按 symbol 的 stepSize 对齐 qtyPerGrid（一次即可，所有档同 qty）
        BigDecimal qty = filtersCache.roundQty(strategy.getSymbol(), strategy.getQtyPerGrid());
        if (qty == null || qty.compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("[SlidingWindow] strategy={} qtyPerGrid {} → 0 after stepSize round; check symbol config",
                    strategy.getId(), strategy.getQtyPerGrid());
            return intents;   // 空列表，start() 会因无 intent 而无声无息成功但没挂单 —— 上层可检测
        }

        for (int i = 1; i <= size; i++) {
            // V1 浮动网格：BUY[i] = center - i × step；SELL[i] = center + i × step
            // ARITHMETIC 用 absStep 绝对减/加；GEOMETRIC 用乘除 factor 逼近
            BigDecimal lowerRaw = computeSlotPriceRaw(state, centerPrice, -i, stepFrac);
            BigDecimal upperRaw = computeSlotPriceRaw(state, centerPrice, +i, stepFrac);
            // 边界保护：低于策略 lowerPrice 或高于 upperPrice 不挂
            BigDecimal lowerBound = strategy.getLowerPrice();
            BigDecimal upperBound = strategy.getUpperPrice();
            BigDecimal lowerPrice = (lowerBound != null && lowerRaw.compareTo(lowerBound) < 0)
                    ? null
                    : filtersCache.roundPrice(strategy.getSymbol(), lowerRaw, "BUY");
            BigDecimal upperPrice = (upperBound != null && upperRaw.compareTo(upperBound) > 0)
                    ? null
                    : filtersCache.roundPrice(strategy.getSymbol(), upperRaw, "SELL");
            if (lowerPrice == null && upperPrice == null) continue;

            // LONG 侧 BUY 单（等回踩买入 → 累积多头）
            if (state.isLongSideEnabled() && lowerPrice != null) {
                GridSlot lowerSlot = GridSlot.builder()
                        .price(lowerPrice)
                        .gridIndex(-i)
                        .lastActivityAt(LocalDateTime.now())
                        .build();
                state.getLowerSlots().put(lowerPrice, lowerSlot);
                intents.add(GridOrderIntent.builder()
                        .strategyId(strategy.getId())
                        .symbol(strategy.getSymbol())
                        .side("BUY")
                        .positionSide("LONG")
                        .role("OPEN")
                        .price(lowerPrice)
                        .qty(qty)
                        .gridIndex(-i)
                        .actionType(GridOrderIntent.ActionType.PLACE)
                        .build());
            }

            // SHORT 侧 SELL 单（等反弹卖出 → 累积空头）
            if (state.isShortSideEnabled() && upperPrice != null
                    && strategy.getHedgeMode() != null && strategy.getHedgeMode() == 1) {
                GridSlot upperSlot = GridSlot.builder()
                        .price(upperPrice)
                        .gridIndex(i)
                        .lastActivityAt(LocalDateTime.now())
                        .build();
                state.getUpperSlots().put(upperPrice, upperSlot);
                intents.add(GridOrderIntent.builder()
                        .strategyId(strategy.getId())
                        .symbol(strategy.getSymbol())
                        .side("SELL")
                        .positionSide("SHORT")
                        .role("OPEN")
                        .price(upperPrice)
                        .qty(qty)
                        .gridIndex(i)
                        .actionType(GridOrderIntent.ActionType.PLACE)
                        .build());
            }
        }

        log.info("[SlidingWindow] initialized strategy={} center={} step={} intents={}",
                strategy.getId(), centerPrice, stepPct, intents.size());
        return intents;
    }

    /**
     * JVM 重启后重建内存状态（不下单，只恢复 slot 拓扑）。
     * <p>
     * 与 {@link #initialize} 区别：不 clear() 已有 slot；不生成 PLACE intent。
     * 后续由 {@link #recordOrderPlaced} 从 grid_order 表回填每档的挂单 ID。
     * </p>
     */
    public GridState rehydrate(GridStrategy strategy, BigDecimal centerPrice, BigDecimal stepPct) {
        GridState state = getOrCreateState(strategy);
        state.setCenterPrice(centerPrice);
        state.setStepPct(stepPct);
        state.setWindowSizePerSide(strategy.getWindowSizePerSide());
        state.setGridMode(strategy.getGridMode());
        state.setAbsoluteStep(computeAbsoluteStep(strategy));
        state.setLongSideEnabled(strategy.getLongSideEnabled() == null || strategy.getLongSideEnabled() == 1);
        state.setShortSideEnabled(strategy.getShortSideEnabled() == null || strategy.getShortSideEnabled() == 1);
        state.getLowerSlots().clear();
        state.getUpperSlots().clear();

        int size = state.getWindowSizePerSide();
        BigDecimal stepFrac = stepPct.divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP);
        for (int i = 1; i <= size; i++) {
            BigDecimal lowerRaw = computeSlotPriceRaw(state, centerPrice, -i, stepFrac);
            BigDecimal upperRaw = computeSlotPriceRaw(state, centerPrice, +i, stepFrac);
            BigDecimal lowerBound = strategy.getLowerPrice();
            BigDecimal upperBound = strategy.getUpperPrice();
            BigDecimal lowerPrice = (lowerBound != null && lowerRaw.compareTo(lowerBound) < 0)
                    ? null
                    : filtersCache.roundPrice(strategy.getSymbol(), lowerRaw, "BUY");
            BigDecimal upperPrice = (upperBound != null && upperRaw.compareTo(upperBound) > 0)
                    ? null
                    : filtersCache.roundPrice(strategy.getSymbol(), upperRaw, "SELL");
            if (state.isLongSideEnabled() && lowerPrice != null) {
                state.getLowerSlots().put(lowerPrice, GridSlot.builder()
                        .price(lowerPrice).gridIndex(-i)
                        .lastActivityAt(LocalDateTime.now()).build());
            }
            if (state.isShortSideEnabled() && upperPrice != null
                    && strategy.getHedgeMode() != null && strategy.getHedgeMode() == 1) {
                state.getUpperSlots().put(upperPrice, GridSlot.builder()
                        .price(upperPrice).gridIndex(i)
                        .lastActivityAt(LocalDateTime.now()).build());
            }
        }
        log.info("[SlidingWindow] rehydrated strategy={} center={} step={} slots={}+{}",
                strategy.getId(), centerPrice, stepPct,
                state.getLowerSlots().size(), state.getUpperSlots().size());
        return state;
    }

    // ─── 成交响应 ────────────────────────────────────────────

    /**
     * 记录挂单 ID（下单成功后回调）。
     */
    public void recordOrderPlaced(long strategyId, BigDecimal gridPrice, String positionSide,
                                   Long orderId, String clientId) {
        GridState state = states.get(strategyId);
        if (state == null) return;
        GridSlot slot;
        if (state.getLowerSlots().containsKey(gridPrice)) {
            slot = state.getLowerSlots().get(gridPrice);
        } else if (state.getUpperSlots().containsKey(gridPrice)) {
            slot = state.getUpperSlots().get(gridPrice);
        } else {
            // V1 浮动：CLOSE 可能挂在初始窗口之外，懒创建 slot 以便追踪。
            // gridIndex 从 (gridPrice - centerPrice) / step 反推，保留中枢相对语义。
            int gridIdx = 0;
            BigDecimal absStep = state.getAbsoluteStep();
            if (absStep != null && absStep.signum() > 0 && state.getCenterPrice() != null) {
                gridIdx = gridPrice.subtract(state.getCenterPrice())
                        .divide(absStep, 0, RoundingMode.HALF_UP).intValue();
            }
            slot = GridSlot.builder()
                    .price(gridPrice)
                    .gridIndex(gridIdx)
                    .lastActivityAt(LocalDateTime.now())
                    .build();
            // 按 positionSide 归到对应侧的 map；CLOSE 本身可能落在"相反侧"的价位
            // （LONG CLOSE 是 SELL @ 上方 → upperSlots；SHORT CLOSE 是 BUY @ 下方 → lowerSlots）
            if (state.getCenterPrice() != null && gridPrice.compareTo(state.getCenterPrice()) > 0) {
                state.getUpperSlots().putIfAbsent(gridPrice, slot);
                slot = state.getUpperSlots().get(gridPrice);
            } else {
                state.getLowerSlots().putIfAbsent(gridPrice, slot);
                slot = state.getLowerSlots().get(gridPrice);
            }
            log.info("[SlidingWindow] strategy={} lazy-created slot @{} gridIdx={} (likely CLOSE out of initial window)",
                    strategyId, gridPrice, gridIdx);
        }
        if ("LONG".equalsIgnoreCase(positionSide)) {
            slot.setLongSideOrderId(orderId);
            slot.setLongSideClientId(clientId);
        } else if ("SHORT".equalsIgnoreCase(positionSide)) {
            slot.setShortSideOrderId(orderId);
            slot.setShortSideClientId(clientId);
        }
    }

    /**
     * 挂单成交响应 —— 生成补挂配对单意图 + 判断是否需要 reshuffle。
     */
    public FillResponse onOrderFilled(GridStrategy strategy, BigDecimal gridPrice, String side,
                                       String positionSide, String role, int gridIndex,
                                       BigDecimal filledQty, BigDecimal fillPrice) {
        GridState state = states.get(strategy.getId());
        if (state == null) return new FillResponse(List.of(), false);

        // 找到本次成交对应的 slot（原槽价即 gridPrice）
        GridSlot slot = null;
        if (state.getLowerSlots().containsKey(gridPrice)) {
            slot = state.getLowerSlots().get(gridPrice);
        } else if (state.getUpperSlots().containsKey(gridPrice)) {
            slot = state.getUpperSlots().get(gridPrice);
        }

        List<GridOrderIntent> intents = new ArrayList<>();

        // ═══════════════════════════════════════════════════════════════════
        // OPEN 成交 → 挂 CLOSE + 标记 POSITION_HELD（V1 不变量：不再同价 rearm）
        // ═══════════════════════════════════════════════════════════════════
        if ("OPEN".equalsIgnoreCase(role)) {
            // OPEN 成交后，slot 的 orderId 清空但 positionHeld=true
            if (slot != null) {
                if ("LONG".equalsIgnoreCase(positionSide)) {
                    slot.setLongSideOrderId(null);
                    slot.setLongSideClientId(null);
                    slot.setLongPositionHeld(true);
                } else if ("SHORT".equalsIgnoreCase(positionSide)) {
                    slot.setShortSideOrderId(null);
                    slot.setShortSideClientId(null);
                    slot.setShortPositionHeld(true);
                }
            }

            BigDecimal closePrice;
            String closeSide = "LONG".equalsIgnoreCase(positionSide) ? "SELL" : "BUY";

            // V1 浮动网格：反手 CLOSE 价 = 挂单价 ± 1 step（与滑点无关，与 gridPrice 一一对齐）
            // LONG BUY 成交 @gridPrice → SELL CLOSE @ gridPrice + step
            // SHORT SELL 成交 @gridPrice → BUY CLOSE @ gridPrice − step
            BigDecimal stepFrac = state.getStepPct().divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP);
            int direction = "LONG".equalsIgnoreCase(positionSide) ? +1 : -1;
            closePrice = computeSlotPriceRaw(state, gridPrice, direction, stepFrac);
            closePrice = filtersCache.roundPrice(strategy.getSymbol(), closePrice, closeSide);
            BigDecimal closeQty = filtersCache.roundQty(strategy.getSymbol(), filledQty);
            if (closeQty == null || closeQty.compareTo(BigDecimal.ZERO) <= 0) {
                log.warn("[SlidingWindow] strategy={} CLOSE qty {} → 0 after stepSize round; skip",
                        strategy.getId(), filledQty);
                return new FillResponse(List.of(), shouldReshuffle(strategy, state, fillPrice));
            }

            // CLOSE 平仓单（配对止盈，reduceOnly 由下单层强制）
            intents.add(GridOrderIntent.builder()
                    .strategyId(strategy.getId())
                    .symbol(strategy.getSymbol())
                    .side(closeSide).positionSide(positionSide).role("CLOSE")
                    .price(closePrice).qty(closeQty)
                    .actionType(GridOrderIntent.ActionType.PLACE)
                    .build());

            log.debug("[SlidingWindow] strategy={} {} OPEN filled @{} → CLOSE @{} + slot HELD",
                    strategy.getId(), positionSide, gridPrice, closePrice);

        // ═══════════════════════════════════════════════════════════════════
        // CLOSE 成交 → 释放两个 slot 的 POSITION_HELD（滑动窗口下轮自动重挂 OPEN）
        // ═══════════════════════════════════════════════════════════════════
        } else if ("CLOSE".equalsIgnoreCase(role)) {
            // 释放本 slot（CLOSE 单挂着的价位）
            if (slot != null) {
                if ("LONG".equalsIgnoreCase(positionSide)) {
                    slot.setLongPositionHeld(false);
                    slot.setLongSideOrderId(null);
                    slot.setLongSideClientId(null);
                } else if ("SHORT".equalsIgnoreCase(positionSide)) {
                    slot.setShortPositionHeld(false);
                    slot.setShortSideOrderId(null);
                    slot.setShortSideClientId(null);
                }
            }
            // 释放配对 OPEN 单原 slot（原 gridIndex 反推）
            // LONG 头寸：OPEN@P[i-1] BUY  → CLOSE@P[i] SELL   → 释放 P[i-1]
            // SHORT 头寸：OPEN@P[i+1] SELL → CLOSE@P[i] BUY   → 释放 P[i+1]
            // V1 浮动：origin OPEN 价 = CLOSE 挂单价 ∓ 1 step
            // LONG CLOSE @gridPrice → origin BUY @ gridPrice − step
            // SHORT CLOSE @gridPrice → origin SELL @ gridPrice + step
            BigDecimal stepFrac = state.getStepPct().divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP);
            int originDirection = "LONG".equalsIgnoreCase(positionSide) ? -1 : +1;
            BigDecimal originOpenPrice = computeSlotPriceRaw(state, gridPrice, originDirection, stepFrac);
            originOpenPrice = filtersCache.roundPrice(strategy.getSymbol(), originOpenPrice,
                    "LONG".equalsIgnoreCase(positionSide) ? "BUY" : "SELL");
            GridSlot originSlot = state.getLowerSlots().get(originOpenPrice);
            if (originSlot == null) originSlot = state.getUpperSlots().get(originOpenPrice);
            if (originSlot != null) {
                if ("LONG".equalsIgnoreCase(positionSide)) {
                    originSlot.setLongPositionHeld(false);
                } else {
                    originSlot.setShortPositionHeld(false);
                }
            } else {
                log.debug("[SlidingWindow] strategy={} CLOSE filled @{} but origin slot @{} not in current window; " +
                                "OK (window drifted); no held to release",
                        strategy.getId(), gridPrice, originOpenPrice);
            }

            log.debug("[SlidingWindow] strategy={} {} CLOSE filled @{} → released slot @{}",
                    strategy.getId(), positionSide, gridPrice, originOpenPrice);

            // V1 关键：CLOSE 释放 slot 后立即在原 OPEN 价重挂 —— 由 refillEmptySlots 统一处理
            // （只挂 EMPTY 的 slot，同价位若在 HELD 就等下轮）
            intents.addAll(refillEmptySlots(strategy));
        }

        // 判断价格是否漂移过大 → 建议 reshuffle
        boolean suggestReshuffle = shouldReshuffle(strategy, state, fillPrice);
        return new FillResponse(intents, suggestReshuffle);
    }

    /**
     * 补挂缺失的 OPEN 单 —— 滑动窗口 reconcile 时调用（DESIGN_V1_SIMPLE §1.4）。
     * <p>
     * 遍历当前窗口内所有 slot，找出 SlotState.EMPTY 的（即 orderId=null 且 !positionHeld），
     * 生成 PLACE OPEN 意图。
     * </p>
     */
    /** V1 refill 预占位 sentinel：置入 orderId 表示"已生成 PLACE 意图但真实 orderId 未回填" */
    public static final long PENDING_PLACE_SENTINEL = -1L;

    public List<GridOrderIntent> refillEmptySlots(GridStrategy strategy) {
        GridState state = states.get(strategy.getId());
        if (state == null) return List.of();

        // 防御性锁：同策略内 refill 序列化，避免并发 CLOSE 时同 slot 多次生成 PLACE
        var lock = refillLocks.computeIfAbsent(strategy.getId(),
                k -> new java.util.concurrent.locks.ReentrantLock());
        lock.lock();
        try {
            List<GridOrderIntent> intents = new ArrayList<>();
            BigDecimal qty = filtersCache.roundQty(strategy.getSymbol(), strategy.getQtyPerGrid());
            if (qty == null || qty.compareTo(BigDecimal.ZERO) <= 0) return intents;

            // LONG 侧（下方 BUY OPEN）
            if (state.isLongSideEnabled()) {
                for (GridSlot slot : state.getLowerSlots().values()) {
                    if (slot.longState() == GridSlot.SlotState.EMPTY) {
                        // 预占位 —— 下轮 refill 看到 orderId != null 即视为 OPEN_ORDER_LIVE 跳过
                        slot.setLongSideOrderId(PENDING_PLACE_SENTINEL);
                        intents.add(GridOrderIntent.builder()
                                .strategyId(strategy.getId())
                                .symbol(strategy.getSymbol())
                                .side("BUY").positionSide("LONG").role("OPEN")
                                .price(slot.getPrice()).qty(qty)
                                .gridIndex(slot.getGridIndex())
                                .actionType(GridOrderIntent.ActionType.PLACE)
                                .build());
                    }
                }
            }
            // SHORT 侧（上方 SELL OPEN）
            if (state.isShortSideEnabled() && strategy.getHedgeMode() != null && strategy.getHedgeMode() == 1) {
                for (GridSlot slot : state.getUpperSlots().values()) {
                    if (slot.shortState() == GridSlot.SlotState.EMPTY) {
                        slot.setShortSideOrderId(PENDING_PLACE_SENTINEL);
                        intents.add(GridOrderIntent.builder()
                                .strategyId(strategy.getId())
                                .symbol(strategy.getSymbol())
                                .side("SELL").positionSide("SHORT").role("OPEN")
                                .price(slot.getPrice()).qty(qty)
                                .gridIndex(slot.getGridIndex())
                                .actionType(GridOrderIntent.ActionType.PLACE)
                                .build());
                    }
                }
            }
            return intents;
        } finally {
            lock.unlock();
        }
    }

    /**
     * V1 保护：PLACE 失败或超时未回填 orderId 时，把 sentinel 复位回 null，
     * 让下一轮 refill 有机会重挂。由 OrderIntentExecutor 在失败路径显式调用。
     */
    public void clearPendingSentinel(long strategyId, BigDecimal gridPrice, String positionSide) {
        GridState state = states.get(strategyId);
        if (state == null) return;
        GridSlot slot = state.getLowerSlots().get(gridPrice);
        if (slot == null) slot = state.getUpperSlots().get(gridPrice);
        if (slot == null) return;
        if ("LONG".equalsIgnoreCase(positionSide)
                && slot.getLongSideOrderId() != null && slot.getLongSideOrderId() == PENDING_PLACE_SENTINEL) {
            slot.setLongSideOrderId(null);
            log.debug("[SlidingWindow] strategy={} cleared LONG sentinel @{}", strategyId, gridPrice);
        } else if ("SHORT".equalsIgnoreCase(positionSide)
                && slot.getShortSideOrderId() != null && slot.getShortSideOrderId() == PENDING_PLACE_SENTINEL) {
            slot.setShortSideOrderId(null);
            log.debug("[SlidingWindow] strategy={} cleared SHORT sentinel @{}", strategyId, gridPrice);
        }
    }

    /**
     * V1 浮动网格：ARITHMETIC 模式下从策略几何反推绝对 step（USDT）；GEOMETRIC 模式返回 null。
     * 保证 ARITHMETIC 每格严格等于 (upper - lower) / count，不依赖 midPrice 的百分比近似。
     */
    private BigDecimal computeAbsoluteStep(GridStrategy strategy) {
        if (!"ARITHMETIC".equalsIgnoreCase(strategy.getGridMode())) return null;
        if (strategy.getLowerPrice() == null || strategy.getUpperPrice() == null
                || strategy.getGridCount() == null || strategy.getGridCount() < 2) {
            return null;
        }
        if (strategy.getLowerPrice().signum() <= 0
                || strategy.getUpperPrice().compareTo(strategy.getLowerPrice()) <= 0) {
            return null;
        }
        return strategy.getUpperPrice().subtract(strategy.getLowerPrice())
                .divide(BigDecimal.valueOf(strategy.getGridCount()), 8, RoundingMode.HALF_UP);
    }

    /**
     * V1 浮动网格核心：从 anchor 出发按 offset 计算 slot 价。
     * <ul>
     *   <li>ARITHMETIC (absStep 存在)：{@code anchor + offset × absStep} —— 严格线性，无浮点乘除累积</li>
     *   <li>GEOMETRIC / V2 fallback：{@code anchor × (1 ± stepFrac × |offset|)} —— 线性逼近</li>
     * </ul>
     * offset 正数=上方 (SELL/CLOSE)，负数=下方 (BUY/CLOSE)。
     */
    private BigDecimal computeSlotPriceRaw(GridState state, BigDecimal anchor, int offset, BigDecimal stepFrac) {
        BigDecimal absStep = state.getAbsoluteStep();
        if (absStep != null) {
            return anchor.add(absStep.multiply(BigDecimal.valueOf(offset)));
        }
        // GEOMETRIC 或 V2：沿用 stepPct 逼近
        BigDecimal factor = BigDecimal.ONE.add(stepFrac.multiply(BigDecimal.valueOf(Math.abs(offset))));
        if (offset >= 0) {
            return anchor.multiply(factor);
        } else {
            return anchor.divide(factor, 12, RoundingMode.HALF_UP);
        }
    }

    private boolean shouldReshuffle(GridStrategy strategy, GridState state, BigDecimal lastFillPrice) {
        BigDecimal center = state.getCenterPrice();
        BigDecimal stepFrac = state.getStepPct().divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP);
        int gridsMoved = lastFillPrice.subtract(center)
                .abs()
                .divide(center.multiply(stepFrac), 0, RoundingMode.DOWN)
                .intValue();
        int threshold = strategy.getReshufflePriceMoveGrids() == null
                ? 5 : strategy.getReshufflePriceMoveGrids();
        if (gridsMoved < threshold) return false;

        // 冷却期检查
        if (state.getLastReshuffleAt() != null) {
            int cooldownSec = strategy.getReshuffleCooldownSec() == null ? 300
                    : strategy.getReshuffleCooldownSec();
            long elapsed = java.time.Duration.between(state.getLastReshuffleAt(), LocalDateTime.now()).getSeconds();
            if (elapsed < cooldownSec) return false;
        }
        return true;
    }

    // ─── 滑动 ────────────────────────────────────────────────

    /**
     * 中枢漂移触发滑动。返回撤 + 挂的意图组合（渐进式，不全撤）。
     *
     * @param newCenter 新中枢价
     */
    public List<GridOrderIntent> shiftWindow(GridStrategy strategy, BigDecimal newCenter) {
        GridState state = states.get(strategy.getId());
        if (state == null) return List.of();

        BigDecimal stepFrac = state.getStepPct().divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP);
        int size = state.getWindowSizePerSide();

        // V1 浮动网格：新窗口价直接从 newCenter ± i × step 计算，边界外跳过
        Set<BigDecimal> newLowerPrices = new HashSet<>();
        Set<BigDecimal> newUpperPrices = new HashSet<>();
        BigDecimal lowerBound = strategy.getLowerPrice();
        BigDecimal upperBound = strategy.getUpperPrice();
        for (int i = 1; i <= size; i++) {
            BigDecimal lowerRaw = computeSlotPriceRaw(state, newCenter, -i, stepFrac);
            BigDecimal upperRaw = computeSlotPriceRaw(state, newCenter, +i, stepFrac);
            if (lowerBound == null || lowerRaw.compareTo(lowerBound) >= 0) {
                newLowerPrices.add(filtersCache.roundPrice(strategy.getSymbol(), lowerRaw, "BUY"));
            }
            if (upperBound == null || upperRaw.compareTo(upperBound) <= 0) {
                newUpperPrices.add(filtersCache.roundPrice(strategy.getSymbol(), upperRaw, "SELL"));
            }
        }
        BigDecimal alignedQty = filtersCache.roundQty(strategy.getSymbol(), strategy.getQtyPerGrid());

        List<GridOrderIntent> intents = new ArrayList<>();

        // 撤销"远离新中枢"的 slot（价格不在新窗口里）
        // V1 保护：POSITION_HELD 的 slot 不能丢，否则 CLOSE 成交时无法定位到原 slot 释放 held 状态
        List<BigDecimal> toRemoveLower = new ArrayList<>();
        for (BigDecimal price : state.getLowerSlots().keySet()) {
            if (!newLowerPrices.contains(price)) {
                GridSlot slot = state.getLowerSlots().get(price);
                // 该 slot 有未平多头 → 保留（等 CLOSE 成交后再自然清理）
                if (slot.isLongPositionHeld()) {
                    log.debug("[SlidingWindow] strategy={} shift preserves held slot @{} LONG",
                            strategy.getId(), price);
                    continue;
                }
                // 只撤 OPEN 单（CLOSE 单不会挂在这里，因为 CLOSE 挂在另一格）
                // 跳过 sentinel 避免向交易所发无效撤单
                Long longId = slot.getLongSideOrderId();
                if (longId != null && longId != PENDING_PLACE_SENTINEL) {
                    intents.add(GridOrderIntent.cancel(strategy.getId(), strategy.getSymbol(),
                            longId, price, "LONG"));
                }
                toRemoveLower.add(price);
            }
        }
        for (BigDecimal p : toRemoveLower) state.getLowerSlots().remove(p);

        List<BigDecimal> toRemoveUpper = new ArrayList<>();
        for (BigDecimal price : state.getUpperSlots().keySet()) {
            if (!newUpperPrices.contains(price)) {
                GridSlot slot = state.getUpperSlots().get(price);
                if (slot.isShortPositionHeld()) {
                    log.debug("[SlidingWindow] strategy={} shift preserves held slot @{} SHORT",
                            strategy.getId(), price);
                    continue;
                }
                Long shortId = slot.getShortSideOrderId();
                if (shortId != null && shortId != PENDING_PLACE_SENTINEL) {
                    intents.add(GridOrderIntent.cancel(strategy.getId(), strategy.getSymbol(),
                            shortId, price, "SHORT"));
                }
                toRemoveUpper.add(price);
            }
        }
        for (BigDecimal p : toRemoveUpper) state.getUpperSlots().remove(p);

        // 挂新窗口里没有的 slot
        int i = 1;
        for (BigDecimal newLower : newLowerPrices) {
            if (!state.getLowerSlots().containsKey(newLower) && state.isLongSideEnabled()) {
                state.getLowerSlots().put(newLower, GridSlot.builder()
                        .price(newLower).gridIndex(-i)
                        .lastActivityAt(LocalDateTime.now()).build());
                intents.add(GridOrderIntent.builder()
                        .strategyId(strategy.getId())
                        .symbol(strategy.getSymbol())
                        .side("BUY").positionSide("LONG").role("OPEN")
                        .price(newLower).qty(alignedQty)
                        .actionType(GridOrderIntent.ActionType.PLACE).build());
            }
            i++;
        }
        i = 1;
        for (BigDecimal newUpper : newUpperPrices) {
            if (!state.getUpperSlots().containsKey(newUpper) && state.isShortSideEnabled()
                    && strategy.getHedgeMode() != null && strategy.getHedgeMode() == 1) {
                state.getUpperSlots().put(newUpper, GridSlot.builder()
                        .price(newUpper).gridIndex(i)
                        .lastActivityAt(LocalDateTime.now()).build());
                intents.add(GridOrderIntent.builder()
                        .strategyId(strategy.getId())
                        .symbol(strategy.getSymbol())
                        .side("SELL").positionSide("SHORT").role("OPEN")
                        .price(newUpper).qty(alignedQty)
                        .actionType(GridOrderIntent.ActionType.PLACE).build());
            }
            i++;
        }

        state.setCenterPrice(newCenter);
        state.setLastReshuffleAt(LocalDateTime.now());
        log.info("[SlidingWindow] strategy={} shifted center → {}, intents={}",
                strategy.getId(), newCenter, intents.size());
        return intents;
    }

    // ─── 分侧控制（Layer 2/3 用）────────────────────────────

    public List<GridOrderIntent> pauseSide(GridStrategy strategy, String positionSide) {
        GridState state = states.get(strategy.getId());
        if (state == null) return List.of();
        if ("LONG".equalsIgnoreCase(positionSide)) state.setLongSideEnabled(false);
        else if ("SHORT".equalsIgnoreCase(positionSide)) state.setShortSideEnabled(false);

        // 撤该侧所有开仓单（保留平仓单；跳过 sentinel 避免向交易所发无效撤单）
        List<GridOrderIntent> intents = new ArrayList<>();
        var slotsMap = "LONG".equalsIgnoreCase(positionSide) ? state.getLowerSlots() : state.getUpperSlots();
        for (var e : slotsMap.entrySet()) {
            GridSlot slot = e.getValue();
            Long id = "LONG".equalsIgnoreCase(positionSide) ? slot.getLongSideOrderId() : slot.getShortSideOrderId();
            if (id != null && id != PENDING_PLACE_SENTINEL) {
                intents.add(GridOrderIntent.cancel(strategy.getId(), strategy.getSymbol(), id, e.getKey(), positionSide));
                if ("LONG".equalsIgnoreCase(positionSide)) slot.setLongSideOrderId(null);
                else slot.setShortSideOrderId(null);
            }
        }
        return intents;
    }

    public void resumeSide(long strategyId, String positionSide) {
        GridState state = states.get(strategyId);
        if (state == null) return;
        if ("LONG".equalsIgnoreCase(positionSide)) state.setLongSideEnabled(true);
        else if ("SHORT".equalsIgnoreCase(positionSide)) state.setShortSideEnabled(true);
    }

    // ─── 紧急撤单 ────────────────────────────────────────────

    public List<GridOrderIntent> cancelAll(GridStrategy strategy) {
        GridState state = states.get(strategy.getId());
        if (state == null) return List.of();
        List<GridOrderIntent> intents = new ArrayList<>();
        // V1 修复：每个 slot 上 LONG / SHORT 两侧 orderId 都要撤（LONG CLOSE 是 SELL 挂在 upperSlots，
        // 但 orderId 落在 slot.longSideOrderId；SHORT CLOSE 对称）。
        // 同时跳过 sentinel（PENDING_PLACE_SENTINEL = -1L），避免向交易所发无效撤单。
        for (var e : state.getLowerSlots().entrySet()) {
            addCancelIntentsForSlot(strategy, e.getKey(), e.getValue(), intents);
        }
        for (var e : state.getUpperSlots().entrySet()) {
            addCancelIntentsForSlot(strategy, e.getKey(), e.getValue(), intents);
        }
        state.getLowerSlots().clear();
        state.getUpperSlots().clear();
        return intents;
    }

    private void addCancelIntentsForSlot(GridStrategy strategy, BigDecimal price, GridSlot slot,
                                          List<GridOrderIntent> intents) {
        Long longId = slot.getLongSideOrderId();
        if (longId != null && longId != PENDING_PLACE_SENTINEL) {
            intents.add(GridOrderIntent.cancel(strategy.getId(), strategy.getSymbol(),
                    longId, price, "LONG"));
        }
        Long shortId = slot.getShortSideOrderId();
        if (shortId != null && shortId != PENDING_PLACE_SENTINEL) {
            intents.add(GridOrderIntent.cancel(strategy.getId(), strategy.getSymbol(),
                    shortId, price, "SHORT"));
        }
    }

    // ─── 内部 VO ─────────────────────────────────────────────

    public record FillResponse(List<GridOrderIntent> intents, boolean suggestReshuffle) {}
}

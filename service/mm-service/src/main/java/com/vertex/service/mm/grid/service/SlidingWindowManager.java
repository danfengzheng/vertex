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
            // 每档相对中枢的价格
            BigDecimal factor = BigDecimal.ONE.add(stepFrac.multiply(BigDecimal.valueOf(i)));
            BigDecimal upperRaw = centerPrice.multiply(factor);
            BigDecimal lowerRaw = centerPrice.divide(factor, 12, RoundingMode.HALF_UP);
            // BUY 单 (下侧) → 用 BUY 侧的 tick round（FLOOR，价格向下）
            BigDecimal lowerPrice = filtersCache.roundPrice(strategy.getSymbol(), lowerRaw, "BUY");
            // SELL 单 (上侧) → 用 SELL 侧的 tick round（CEILING，价格向上）
            BigDecimal upperPrice = filtersCache.roundPrice(strategy.getSymbol(), upperRaw, "SELL");

            // LONG 侧 BUY 单（等回踩买入 → 累积多头）
            if (state.isLongSideEnabled()) {
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
            if (state.isShortSideEnabled() && strategy.getHedgeMode() != null && strategy.getHedgeMode() == 1) {
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
        state.setLongSideEnabled(strategy.getLongSideEnabled() == null || strategy.getLongSideEnabled() == 1);
        state.setShortSideEnabled(strategy.getShortSideEnabled() == null || strategy.getShortSideEnabled() == 1);
        state.getLowerSlots().clear();
        state.getUpperSlots().clear();

        int size = state.getWindowSizePerSide();
        BigDecimal stepFrac = stepPct.divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP);
        for (int i = 1; i <= size; i++) {
            BigDecimal factor = BigDecimal.ONE.add(stepFrac.multiply(BigDecimal.valueOf(i)));
            BigDecimal upperPrice = filtersCache.roundPrice(strategy.getSymbol(),
                    centerPrice.multiply(factor), "SELL");
            BigDecimal lowerPrice = filtersCache.roundPrice(strategy.getSymbol(),
                    centerPrice.divide(factor, 12, RoundingMode.HALF_UP), "BUY");
            if (state.isLongSideEnabled()) {
                state.getLowerSlots().put(lowerPrice, GridSlot.builder()
                        .price(lowerPrice).gridIndex(-i)
                        .lastActivityAt(LocalDateTime.now()).build());
            }
            if (state.isShortSideEnabled() && strategy.getHedgeMode() != null && strategy.getHedgeMode() == 1) {
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
            log.warn("[SlidingWindow] strategy={} recordOrderPlaced but no slot at price={}", strategyId, gridPrice);
            return;
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

        // 清 slot 里对应的 orderId
        GridSlot slot = null;
        if (state.getLowerSlots().containsKey(gridPrice)) {
            slot = state.getLowerSlots().get(gridPrice);
        } else if (state.getUpperSlots().containsKey(gridPrice)) {
            slot = state.getUpperSlots().get(gridPrice);
        }
        if (slot != null) {
            if ("LONG".equalsIgnoreCase(positionSide)) {
                slot.setLongSideOrderId(null);
                slot.setLongSideClientId(null);
            } else if ("SHORT".equalsIgnoreCase(positionSide)) {
                slot.setShortSideOrderId(null);
                slot.setShortSideClientId(null);
            }
        }

        List<GridOrderIntent> intents = new ArrayList<>();

        // OPEN 成交 → 挂 CLOSE 平仓单 + 同价位重新武装 OPEN（传统 pending grid 语义）
        if ("OPEN".equalsIgnoreCase(role)) {
            BigDecimal stepFrac = state.getStepPct().divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP);
            BigDecimal closePrice;
            String closeSide;
            if ("LONG".equalsIgnoreCase(positionSide)) {
                // LONG BUY 成交 → 在上方挂 SELL 平仓
                closePrice = fillPrice.multiply(BigDecimal.ONE.add(stepFrac));
                closeSide = "SELL";
            } else {
                // SHORT SELL 成交 → 在下方挂 BUY 平仓
                closePrice = fillPrice.multiply(BigDecimal.ONE.subtract(stepFrac));
                closeSide = "BUY";
            }
            closePrice = filtersCache.roundPrice(strategy.getSymbol(), closePrice, closeSide);
            BigDecimal closeQty = filtersCache.roundQty(strategy.getSymbol(), filledQty);
            if (closeQty == null || closeQty.compareTo(BigDecimal.ZERO) <= 0) {
                log.warn("[SlidingWindow] strategy={} CLOSE qty {} → 0 after stepSize round; skip",
                        strategy.getId(), filledQty);
                return new FillResponse(List.of(), shouldReshuffle(strategy, state, fillPrice));
            }

            // ① CLOSE 平仓单（配对止盈）
            intents.add(GridOrderIntent.builder()
                    .strategyId(strategy.getId())
                    .symbol(strategy.getSymbol())
                    .side(closeSide).positionSide(positionSide).role("CLOSE")
                    .price(closePrice).qty(closeQty)
                    .actionType(GridOrderIntent.ActionType.PLACE)
                    .build());

            // ② 同价位 rearm OPEN —— 网格永远满档（传统 pending grid）
            BigDecimal openQty = filtersCache.roundQty(strategy.getSymbol(), strategy.getQtyPerGrid());
            if (openQty != null && openQty.compareTo(BigDecimal.ZERO) > 0) {
                intents.add(GridOrderIntent.builder()
                        .strategyId(strategy.getId())
                        .symbol(strategy.getSymbol())
                        .side(side).positionSide(positionSide).role("OPEN")
                        .price(gridPrice)   // 原槽价（已 tick 对齐）
                        .qty(openQty)
                        .gridIndex(gridIndex)
                        .actionType(GridOrderIntent.ActionType.PLACE)
                        .build());
                log.debug("[SlidingWindow] strategy={} rearm {} OPEN @{} after fill",
                        strategy.getId(), positionSide, gridPrice);
            }
        }

        // 判断价格是否漂移过大 → 建议 reshuffle
        boolean suggestReshuffle = shouldReshuffle(strategy, state, fillPrice);
        return new FillResponse(intents, suggestReshuffle);
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

        // 计算新窗口的价格集合（tick 对齐，SELL 侧 CEILING/BUY 侧 FLOOR）
        Set<BigDecimal> newLowerPrices = new HashSet<>();
        Set<BigDecimal> newUpperPrices = new HashSet<>();
        for (int i = 1; i <= size; i++) {
            BigDecimal factor = BigDecimal.ONE.add(stepFrac.multiply(BigDecimal.valueOf(i)));
            newUpperPrices.add(filtersCache.roundPrice(strategy.getSymbol(),
                    newCenter.multiply(factor), "SELL"));
            newLowerPrices.add(filtersCache.roundPrice(strategy.getSymbol(),
                    newCenter.divide(factor, 12, RoundingMode.HALF_UP), "BUY"));
        }
        BigDecimal alignedQty = filtersCache.roundQty(strategy.getSymbol(), strategy.getQtyPerGrid());

        List<GridOrderIntent> intents = new ArrayList<>();

        // 撤销"远离新中枢"的 slot（价格不在新窗口里）
        List<BigDecimal> toRemoveLower = new ArrayList<>();
        for (BigDecimal price : state.getLowerSlots().keySet()) {
            if (!newLowerPrices.contains(price)) {
                GridSlot slot = state.getLowerSlots().get(price);
                if (slot.getLongSideOrderId() != null) {
                    intents.add(GridOrderIntent.cancel(strategy.getId(), strategy.getSymbol(),
                            slot.getLongSideOrderId(), price, "LONG"));
                }
                toRemoveLower.add(price);
            }
        }
        for (BigDecimal p : toRemoveLower) state.getLowerSlots().remove(p);

        List<BigDecimal> toRemoveUpper = new ArrayList<>();
        for (BigDecimal price : state.getUpperSlots().keySet()) {
            if (!newUpperPrices.contains(price)) {
                GridSlot slot = state.getUpperSlots().get(price);
                if (slot.getShortSideOrderId() != null) {
                    intents.add(GridOrderIntent.cancel(strategy.getId(), strategy.getSymbol(),
                            slot.getShortSideOrderId(), price, "SHORT"));
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

        // 撤该侧所有开仓单（保留平仓单）
        List<GridOrderIntent> intents = new ArrayList<>();
        var slotsMap = "LONG".equalsIgnoreCase(positionSide) ? state.getLowerSlots() : state.getUpperSlots();
        for (var e : slotsMap.entrySet()) {
            GridSlot slot = e.getValue();
            Long id = "LONG".equalsIgnoreCase(positionSide) ? slot.getLongSideOrderId() : slot.getShortSideOrderId();
            if (id != null) {
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
        for (var e : state.getLowerSlots().entrySet()) {
            GridSlot slot = e.getValue();
            if (slot.getLongSideOrderId() != null) {
                intents.add(GridOrderIntent.cancel(strategy.getId(), strategy.getSymbol(),
                        slot.getLongSideOrderId(), e.getKey(), "LONG"));
            }
        }
        for (var e : state.getUpperSlots().entrySet()) {
            GridSlot slot = e.getValue();
            if (slot.getShortSideOrderId() != null) {
                intents.add(GridOrderIntent.cancel(strategy.getId(), strategy.getSymbol(),
                        slot.getShortSideOrderId(), e.getKey(), "SHORT"));
            }
        }
        state.getLowerSlots().clear();
        state.getUpperSlots().clear();
        return intents;
    }

    // ─── 内部 VO ─────────────────────────────────────────────

    public record FillResponse(List<GridOrderIntent> intents, boolean suggestReshuffle) {}
}

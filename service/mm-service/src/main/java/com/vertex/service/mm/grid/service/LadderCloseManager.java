package com.vertex.service.mm.grid.service;

import com.vertex.service.mm.binance.SymbolFiltersCache;
import com.vertex.service.mm.grid.entity.GridStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * 梯队平仓管理器 —— 用户核心创新："用时间换空间"。
 * <p>
 * 场景：Layer 2B 触发，逆势侧累积仓位，早期趋势。
 * <br>
 * 做法：在**平均成本 ≤ 价格 &lt; 现价**区间内密集挂平仓单，反弹时能触发更多档，
 * 且**每次平仓都是净利润**（因为价 ≥ 平均成本）。
 * <br>
 * 相比 Layer 3 主动减仓：**不认亏**，只是让反弹的效率翻倍。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LadderCloseManager {

    private final SymbolFiltersCache filtersCache;

    /**
     * 计算梯队平仓单意图。
     *
     * @param strategy      策略配置（含 ladder_pct_of_position / ladder_step_count 等）
     * @param positionSide  LONG / SHORT（哪一侧要挂梯队）
     * @param accumulatedQty 累积仓位数量（绝对值）
     * @param avgCost       平均持仓成本
     * @param currentPrice  当前 mark price
     * @return 梯队挂单意图列表（可能为空，如果没有累积仓位）
     */
    public List<GridOrderIntent> computeLadderIntents(GridStrategy strategy, String positionSide,
                                                       BigDecimal accumulatedQty, BigDecimal avgCost,
                                                       BigDecimal currentPrice) {
        if (accumulatedQty == null || accumulatedQty.compareTo(BigDecimal.ZERO) <= 0) return List.of();
        if (avgCost == null || currentPrice == null) return List.of();

        boolean isLong = "LONG".equalsIgnoreCase(positionSide);
        // LONG 场景：反弹 = 价格上涨，平仓单挂在 avgCost..currentPrice 区间（SELL）
        // SHORT 场景：反弹 = 价格下跌，平仓单挂在 currentPrice..avgCost 区间（BUY）

        // 只有"处于浮亏"才有意义（现价对 LONG 来说 < avgCost，或对 SHORT 来说 > avgCost）
        if (isLong && currentPrice.compareTo(avgCost) >= 0) {
            // LONG 已经浮盈 → 走原始配对平仓即可，不需要梯队
            return List.of();
        }
        if (!isLong && currentPrice.compareTo(avgCost) <= 0) {
            return List.of();
        }

        BigDecimal ladderPct = strategy.getLadderPctOfPosition() == null
                ? new BigDecimal("30") : strategy.getLadderPctOfPosition();
        int stepCount = strategy.getLadderStepCount() == null
                ? 5 : strategy.getLadderStepCount();
        boolean floorAtAvg = strategy.getLadderFloorAtAvgCost() == null
                || strategy.getLadderFloorAtAvgCost() == 1;
        BigDecimal reservePct = strategy.getLadderMinReservePct() == null
                ? new BigDecimal("0.05") : strategy.getLadderMinReservePct();
        BigDecimal reserveFrac = reservePct.divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP);

        BigDecimal totalLadderQty = accumulatedQty.multiply(ladderPct)
                .divide(BigDecimal.valueOf(100), 8, RoundingMode.DOWN);
        if (totalLadderQty.compareTo(BigDecimal.ZERO) <= 0) return List.of();

        BigDecimal eachQty = totalLadderQty.divide(BigDecimal.valueOf(stepCount), 8, RoundingMode.DOWN);
        eachQty = filtersCache.roundQty(strategy.getSymbol(), eachQty);
        if (eachQty == null || eachQty.compareTo(BigDecimal.ZERO) <= 0) return List.of();

        // 计算 ladder 价格范围
        BigDecimal ladderCeiling, ladderFloor;
        String closeSide;
        if (isLong) {
            // LONG：梯队价格区间 [avgCost, currentPrice*(1 - reserve)]，close side = SELL
            // 但这里其实是 LONG 浮亏（currentPrice < avgCost），所以 avgCost > currentPrice
            // 反弹时梯队价格应该 > currentPrice 但 ≤ avgCost
            // 但反弹方向朝上，所以取 currentPrice 之上的价位
            // 修正：应该是 [currentPrice*(1+reserve), avgCost*(1+small_gain)]
            ladderFloor = floorAtAvg ? avgCost : currentPrice.multiply(BigDecimal.ONE.add(reserveFrac));
            ladderCeiling = avgCost.multiply(new BigDecimal("1.02"));   // 平均成本 + 2%（小盈利上限）
            closeSide = "SELL";
        } else {
            // SHORT：反弹 = 价格下跌，梯队价格 [avgCost*(1-small_gain), currentPrice*(1 - reserve)]
            ladderFloor = avgCost.multiply(new BigDecimal("0.98"));
            ladderCeiling = floorAtAvg ? avgCost : currentPrice.multiply(BigDecimal.ONE.subtract(reserveFrac));
            closeSide = "BUY";
        }

        if (ladderCeiling.compareTo(ladderFloor) <= 0) return List.of();

        // 等间距 N 档
        BigDecimal stepSize = ladderCeiling.subtract(ladderFloor)
                .divide(BigDecimal.valueOf(Math.max(1, stepCount - 1)), 8, RoundingMode.HALF_UP);

        List<GridOrderIntent> intents = new ArrayList<>();
        for (int i = 0; i < stepCount; i++) {
            BigDecimal price;
            if (isLong) {
                // LONG close SELL：低价格档先触发，所以从 floor → ceiling
                price = ladderFloor.add(stepSize.multiply(BigDecimal.valueOf(i)));
            } else {
                // SHORT close BUY：高价格档先触发（反向），从 ceiling → floor
                price = ladderCeiling.subtract(stepSize.multiply(BigDecimal.valueOf(i)));
            }
            price = filtersCache.roundPrice(strategy.getSymbol(), price, closeSide);
            intents.add(GridOrderIntent.builder()
                    .strategyId(strategy.getId())
                    .symbol(strategy.getSymbol())
                    .side(closeSide)
                    .positionSide(positionSide)
                    .role("LADDER_CLOSE")
                    .price(price)
                    .qty(eachQty)
                    .actionType(GridOrderIntent.ActionType.PLACE)
                    .build());
        }

        log.info("[LadderClose] strategy={} side={} accumulated={} avgCost={} current={} → {} ladders",
                strategy.getId(), positionSide, accumulatedQty, avgCost, currentPrice, intents.size());
        return intents;
    }
}

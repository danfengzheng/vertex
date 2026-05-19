package com.vertex.service.order.task;

import com.vertex.model.entity.strategy.Strategy;
import com.vertex.model.entity.trading.Position;
import com.vertex.model.entity.trading.PositionSide;
import com.vertex.model.entity.trading.PositionStatus;
import com.vertex.service.order.mapper.StrategyRefMapper;
import com.vertex.service.order.service.impl.PaperTradingService;
import com.vertex.service.order.service.impl.PositionManagementService;
import com.vertex.service.order.service.impl.TradeExecutionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * 持仓价格刷新 & 止盈止损检查定时任务
 * <p>
 * 每 10 秒扫描所有 OPEN 持仓：
 * 1. 刷新 currentPrice、unrealizedPnl 并持久化到数据库
 * 2. 检查是否触达止损价或止盈价，触发后自动平仓
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "vertex.trading", name = "enabled", havingValue = "true")
public class StopLossTakeProfitTask {

    private final PositionManagementService positionManagementService;
    private final PaperTradingService paperTradingService;
    private final TradeExecutionService tradeExecutionService;
    private final StrategyRefMapper strategyRefMapper;

    @Scheduled(fixedDelayString = "${vertex.trading.sl-tp-check-interval:10000}")
    public void checkStopLossTakeProfit() {
        List<Position> openPositions = positionManagementService.findAllOpenPositions();
        if (openPositions.isEmpty()) {
            return;
        }

        for (Position position : openPositions) {
            try {
                // 获取当前价格
                BigDecimal currentPrice = paperTradingService.getCurrentPrice(
                        position.getExchange(), position.getSymbol());
                if (currentPrice == null) {
                    continue;
                }

                // 更新当前价格和未实现盈亏（区分 LONG/SHORT 方向）
                position.setCurrentPrice(currentPrice);
                if (position.getEntryPrice() != null
                        && position.getQuantity() != null
                        && position.getQuantity().compareTo(BigDecimal.ZERO) > 0) {
                    boolean shortPos = position.getSide() == PositionSide.SHORT;
                    // LONG：盈亏 = (当前价 - 入场价) × 数量
                    // SHORT：盈亏 = (入场价 - 当前价) × 数量
                    BigDecimal unrealizedPnl = shortPos
                            ? position.getEntryPrice().subtract(currentPrice)
                                    .multiply(position.getQuantity())
                                    .setScale(10, RoundingMode.HALF_UP)
                            : currentPrice.subtract(position.getEntryPrice())
                                    .multiply(position.getQuantity())
                                    .setScale(10, RoundingMode.HALF_UP);
                    position.setUnrealizedPnl(unrealizedPnl);
                }

                boolean triggered = false;
                boolean isShort = position.getSide() == PositionSide.SHORT;
                // 标记本次循环是否已在峰值追踪块中提前持久化，避免末尾重复写入
                boolean priceUpdated = false;

                // ── ① 固定止损（固定百分比 / ATR）── 优先级最高 ──────────────
                // LONG：价格下跌触达止损（currentPrice <= stopLoss）
                // SHORT：价格上涨触达止损（currentPrice >= stopLoss）
                if (!triggered && position.getStopLoss() != null) {
                    boolean slTriggered = isShort
                            ? currentPrice.compareTo(position.getStopLoss()) >= 0
                            : currentPrice.compareTo(position.getStopLoss()) <= 0;
                    if (slTriggered) {
                        log.info("[SL/TP Task] Stop loss triggered: {} {} side={} currentPrice={} stopLoss={}",
                                position.getExchange(), position.getSymbol(), position.getSide(),
                                currentPrice, position.getStopLoss());
                        tradeExecutionService.executeStopLossClose(position);
                        triggered = true;
                    }
                }

                // ── ② SuperTrend 动态止损 ── 优先级次之 ──────────────────────
                // 由 StrategyEngineService 在每根 K 线收盘后写入，null / <= 0 表示未启用。
                // LONG：currentPrice <= superTrendStopLoss 触发
                // SHORT：currentPrice >= superTrendStopLoss 触发
                if (!triggered && position.getSuperTrendStopLoss() != null
                        && position.getSuperTrendStopLoss().compareTo(BigDecimal.ZERO) > 0) {
                    boolean stTriggered = isShort
                            ? currentPrice.compareTo(position.getSuperTrendStopLoss()) >= 0
                            : currentPrice.compareTo(position.getSuperTrendStopLoss()) <= 0;
                    if (stTriggered) {
                        log.info("[SL/TP Task] SuperTrend stop triggered: {} {} side={} currentPrice={} superTrendStopLoss={}",
                                position.getExchange(), position.getSymbol(), position.getSide(),
                                currentPrice, position.getSuperTrendStopLoss());
                        tradeExecutionService.executeStopLossClose(position);
                        triggered = true;
                    }
                }

                // ── ③ 峰值回撤止损（trailingDropPct）── 优先级最低 ───────────
                // 每次价格刷新时：多头追踪最高价，空头追踪最低价；
                // 从峰值回撤超过 trailingDropPct% 时触发平仓。
                if (!triggered && position.getStrategyId() != null) {
                    Strategy strategy = strategyRefMapper.selectById(position.getStrategyId());
                    if (strategy != null && strategy.getTrailingDropPct() != null
                            && strategy.getTrailingDropPct().compareTo(BigDecimal.ZERO) > 0) {
                        BigDecimal dropPct = strategy.getTrailingDropPct()
                                .divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP);
                        boolean peakUpdated = false;
                        if (!isShort) {
                            // LONG：追踪最高价
                            BigDecimal peak = position.getHighestPrice() != null
                                    ? position.getHighestPrice() : position.getEntryPrice();
                            if (currentPrice.compareTo(peak) > 0) {
                                position.setHighestPrice(currentPrice);
                                peak = currentPrice;
                                peakUpdated = true;
                            }
                            BigDecimal dropTrigger = peak.multiply(BigDecimal.ONE.subtract(dropPct));
                            if (currentPrice.compareTo(dropTrigger) <= 0) {
                                log.info("[Peak Drop SL] LONG triggered: {} {} peak={} dropPct={}% currentPrice={}",
                                        position.getExchange(), position.getSymbol(),
                                        peak, strategy.getTrailingDropPct(), currentPrice);
                                tradeExecutionService.executeStopLossClose(position);
                                triggered = true;
                            }
                        } else {
                            // SHORT：追踪最低价
                            BigDecimal trough = position.getLowestPrice() != null
                                    ? position.getLowestPrice() : position.getEntryPrice();
                            if (currentPrice.compareTo(trough) < 0) {
                                position.setLowestPrice(currentPrice);
                                trough = currentPrice;
                                peakUpdated = true;
                            }
                            BigDecimal dropTrigger = trough.multiply(BigDecimal.ONE.add(dropPct));
                            if (currentPrice.compareTo(dropTrigger) >= 0) {
                                log.info("[Peak Drop SL] SHORT triggered: {} {} trough={} dropPct={}% currentPrice={}",
                                        position.getExchange(), position.getSymbol(),
                                        trough, strategy.getTrailingDropPct(), currentPrice);
                                tradeExecutionService.executeStopLossClose(position);
                                triggered = true;
                            }
                        }
                        // 峰值更新但未触发止损：持久化新峰值（设置标记，避免末尾重复写入）
                        if (!triggered && peakUpdated) {
                            positionManagementService.updateCurrentPrice(position);
                            priceUpdated = true;
                        }
                    }
                }

                // 止盈检查（区分方向 + 分阶段优先）
                // ── 分阶段止盈（size1>0 即启用）：按 takeProfitStage 推进，
                //    Σ size = 100% → 末档扫尾全平；Σ size < 100% → 末档部分平剩 runner，
                //    runner 由后续止损族（固定止损 / SuperTrend / 峰值回撤）接管退出。
                // ── 单级止盈（旧逻辑）：分阶段未启用时保留向后兼容。
                if (!triggered) {
                    Strategy stratForTp = position.getStrategyId() != null
                            ? strategyRefMapper.selectById(position.getStrategyId()) : null;
                    if (stratForTp != null && isStagedTpEnabled(stratForTp)) {
                        triggered = handleStagedTakeProfit(position, currentPrice, isShort, stratForTp);
                    } else if (position.getTakeProfit() != null) {
                        boolean tpTriggered = isShort
                                ? currentPrice.compareTo(position.getTakeProfit()) <= 0
                                : currentPrice.compareTo(position.getTakeProfit()) >= 0;
                        if (tpTriggered) {
                            log.info("[SL/TP Task] Take profit triggered: {} {} side={} currentPrice={} takeProfit={}",
                                    position.getExchange(), position.getSymbol(), position.getSide(),
                                    currentPrice, position.getTakeProfit());
                            tradeExecutionService.executeClose(position);
                            triggered = true;
                        }
                    }
                }

                // 未触发止盈止损，且峰值追踪块未提前写入 → 持久化价格更新到数据库
                if (!triggered && !priceUpdated) {
                    positionManagementService.updateCurrentPrice(position);
                }

            } catch (Exception e) {
                log.error("[SL/TP Task] Error checking position {}: {}",
                        position.getId(), e.getMessage(), e);
            }
        }
    }

    /**
     * 是否启用了分阶段止盈：size1 > 0 即启用，启用后与 takeProfitPct / atrTakeProfitMultiplier 互斥。
     */
    private boolean isStagedTpEnabled(Strategy s) {
        return s.getTakeProfitSize1() != null
                && s.getTakeProfitSize1().compareTo(BigDecimal.ZERO) > 0
                && s.getTakeProfitPct1() != null
                && s.getTakeProfitPct1().compareTo(BigDecimal.ZERO) > 0;
    }

    /**
     * 处理分阶段止盈：检查下一档是否触达，若触达则部分平 / 末档扫尾。
     * 「一次扫描只推进一级」：单根 K 线穿越多档时分多轮处理，避免跨阶段状态耦合。
     *
     * @return 本轮是否触发了平仓动作（true = 已触发，外层 triggered 置 true）
     */
    private boolean handleStagedTakeProfit(Position position, BigDecimal currentPrice,
                                            boolean isShort, Strategy strategy) {
        int currentStage = position.getTakeProfitStage() == null ? 0 : position.getTakeProfitStage();
        // 收集已配置的档（pct & size 均 > 0，且必须是连续前缀）
        java.util.List<BigDecimal> pcts  = new java.util.ArrayList<>(3);
        java.util.List<BigDecimal> sizes = new java.util.ArrayList<>(3);
        appendStageIfConfigured(pcts, sizes, strategy.getTakeProfitPct1(), strategy.getTakeProfitSize1());
        if (!pcts.isEmpty()) {
            appendStageIfConfigured(pcts, sizes, strategy.getTakeProfitPct2(), strategy.getTakeProfitSize2());
        }
        if (pcts.size() == 2) {
            appendStageIfConfigured(pcts, sizes, strategy.getTakeProfitPct3(), strategy.getTakeProfitSize3());
        }
        int totalStages = pcts.size();
        if (currentStage >= totalStages) return false;   // 全部档已触发完毕（理论上仓位已平）

        int nextStage = currentStage + 1;
        BigDecimal nextPct = pcts.get(currentStage)
                .divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP);
        BigDecimal entry = position.getEntryPrice();
        BigDecimal trigger = isShort
                ? entry.multiply(BigDecimal.ONE.subtract(nextPct))
                : entry.multiply(BigDecimal.ONE.add(nextPct));

        boolean hit = isShort
                ? currentPrice.compareTo(trigger) <= 0
                : currentPrice.compareTo(trigger) >= 0;
        if (!hit) return false;

        boolean isLastConfigured = (nextStage == totalStages);
        BigDecimal sumSize = BigDecimal.ZERO;
        for (BigDecimal s : sizes) sumSize = sumSize.add(s);
        boolean sumIsFull = sumSize.compareTo(BigDecimal.valueOf(100)) >= 0;

        // 末档扫尾条件：是最后已配置档 + Σ ≥ 100% → 调 executeClose 平剩余全部；
        // 否则按 size_i × initialQuantity 计算 partialQty 走 executePartialClose
        if (isLastConfigured && sumIsFull) {
            log.info("[Staged TP] Last stage sweep: position={} stage {}->{} price={} trigger={}",
                    position.getId(), currentStage, nextStage, currentPrice, trigger);
            tradeExecutionService.executeClose(position);
            return true;
        }

        BigDecimal initialQty = position.getInitialQuantity() != null
                ? position.getInitialQuantity() : position.getQuantity();
        BigDecimal sizePct = sizes.get(currentStage)
                .divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP);
        BigDecimal partialQty = initialQty.multiply(sizePct)
                .setScale(10, RoundingMode.HALF_UP);

        log.info("[Staged TP] Stage {}->{} triggered: position={} pct={}% size={}% price={} trigger={} partialQty={}",
                currentStage, nextStage, position.getId(),
                pcts.get(currentStage), sizes.get(currentStage),
                currentPrice, trigger, partialQty);

        tradeExecutionService.executePartialClose(position, partialQty, nextStage);
        return true;
    }

    private void appendStageIfConfigured(java.util.List<BigDecimal> pcts, java.util.List<BigDecimal> sizes,
                                          BigDecimal pct, BigDecimal size) {
        if (pct != null && pct.compareTo(BigDecimal.ZERO) > 0
                && size != null && size.compareTo(BigDecimal.ZERO) > 0) {
            pcts.add(pct);
            sizes.add(size);
        }
    }
}

package com.vertex.service.order.task;

import com.vertex.model.entity.trading.Position;
import com.vertex.model.entity.trading.PositionStatus;
import com.vertex.service.order.service.impl.PaperTradingService;
import com.vertex.service.order.service.impl.PositionManagementService;
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

                // 更新当前价格和未实现盈亏
                position.setCurrentPrice(currentPrice);
                if (position.getEntryPrice() != null
                        && position.getQuantity() != null
                        && position.getQuantity().compareTo(BigDecimal.ZERO) > 0) {
                    BigDecimal unrealizedPnl = currentPrice.subtract(position.getEntryPrice())
                            .multiply(position.getQuantity())
                            .setScale(10, RoundingMode.HALF_UP);
                    position.setUnrealizedPnl(unrealizedPnl);
                }

                boolean triggered = false;

                // 止损检查
                if (position.getStopLoss() != null
                        && currentPrice.compareTo(position.getStopLoss()) <= 0) {
                    log.info("[SL/TP Task] Stop loss triggered: {} {} currentPrice={} stopLoss={}",
                            position.getExchange(), position.getSymbol(),
                            currentPrice, position.getStopLoss());
                    positionManagementService.closePosition(position, currentPrice);
                    triggered = true;
                }

                // 止盈检查
                if (!triggered && position.getTakeProfit() != null
                        && currentPrice.compareTo(position.getTakeProfit()) >= 0) {
                    log.info("[SL/TP Task] Take profit triggered: {} {} currentPrice={} takeProfit={}",
                            position.getExchange(), position.getSymbol(),
                            currentPrice, position.getTakeProfit());
                    positionManagementService.closePosition(position, currentPrice);
                    triggered = true;
                }

                // 未触发止盈止损 → 持久化价格更新到数据库
                if (!triggered) {
                    positionManagementService.updateCurrentPrice(position);
                }

            } catch (Exception e) {
                log.error("[SL/TP Task] Error checking position {}: {}",
                        position.getId(), e.getMessage(), e);
            }
        }
    }
}

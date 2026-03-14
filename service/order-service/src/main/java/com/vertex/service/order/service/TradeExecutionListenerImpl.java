package com.vertex.service.order.service;

import com.vertex.api.trading.ITradeExecutionListener;
import com.vertex.model.entity.quote.KLine;
import com.vertex.model.entity.strategy.Signal;
import com.vertex.model.entity.strategy.SignalType;
import com.vertex.model.entity.strategy.Strategy;
import com.vertex.service.order.service.impl.TradeExecutionService;

import java.math.BigDecimal;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 交易执行监听器实现
 * <p>
 * 桥接 strategy-service 的信号管道与 order-service 的交易执行。
 * 仅在 vertex.trading.enabled=true 时激活。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "vertex.trading", name = "enabled", havingValue = "true")
public class TradeExecutionListenerImpl implements ITradeExecutionListener {

    private final TradeExecutionService tradeExecutionService;

    @Override
    public void onSignal(Strategy strategy, Signal signal) {
        try {
            tradeExecutionService.executeSignal(strategy, signal);
        } catch (Exception e) {
            log.error("Trade execution failed for strategy [{}] signal [{}]: {}",
                    strategy.getName(), signal.getId(), e.getMessage(), e);
        }
    }

    @Override
    public void onKLineClose(Strategy strategy, BigDecimal atrValue, List<KLine> klines) {
        try {
            tradeExecutionService.updateTrailingStops(strategy, atrValue, klines);
        } catch (Exception e) {
            log.error("Trailing stop update failed for strategy [{}]: {}", strategy.getName(), e.getMessage(), e);
        }
    }

    @Override
    public void onExitConditionCheck(Strategy strategy, SignalType exitSignalType, int signalStrength) {
        try {
            tradeExecutionService.processExitConditions(strategy, exitSignalType, signalStrength);
        } catch (Exception e) {
            log.error("Exit condition check failed for strategy [{}]: {}", strategy.getName(), e.getMessage(), e);
        }
    }
}

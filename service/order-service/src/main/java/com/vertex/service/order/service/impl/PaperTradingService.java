package com.vertex.service.order.service.impl;

import com.vertex.model.entity.quote.KLine;
import com.vertex.model.entity.quote.KLineInterval;
import com.vertex.model.entity.trading.*;
import com.vertex.service.quote.store.KLineStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * 模拟交易服务
 * <p>
 * 使用 KLineStore 获取当前价格进行模拟成交，不调用真实交易所 API。
 * 手续费为 0，订单状态为 SIMULATED。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaperTradingService {

    private final KLineStore klineStore;

    /**
     * 获取当前价格（从 KLineStore 取最新 K线收盘价）
     */
    public BigDecimal getCurrentPrice(String exchange, String symbol) {
        // 使用 1m K线获取最近价格
        List<KLine> klines = klineStore.query(exchange, symbol, KLineInterval.M1, null, null, 1, false);
        if (!klines.isEmpty()) {
            return klines.get(0).getClose();
        }
        // 回退到 5m
        klines = klineStore.query(exchange, symbol, KLineInterval.M5, null, null, 1, false);
        if (!klines.isEmpty()) {
            return klines.get(0).getClose();
        }
        // 回退到 1h
        klines = klineStore.query(exchange, symbol, KLineInterval.H1, null, null, 1, false);
        if (!klines.isEmpty()) {
            return klines.get(0).getClose();
        }
        log.warn("No price data available for {}:{}", exchange, symbol);
        return null;
    }

    /**
     * 模拟成交 — 填充订单的成交信息
     *
     * @param order   订单
     * @param feeRate 手续费率（如 0.001 = 0.1%），为 null 或 0 时不收手续费
     */
    public void simulateFill(Order order, BigDecimal feeRate) {
        BigDecimal currentPrice = getCurrentPrice(order.getExchange(), order.getSymbol());
        if (currentPrice == null) {
            order.setStatus(OrderStatus.REJECTED);
            order.setErrorMsg("No price data available for simulation");
            return;
        }

        BigDecimal fillPrice;
        if (order.getOrderType() == OrderType.LIMIT && order.getPrice() != null) {
            // LIMIT 订单：检查价格是否能成交
            if (order.getSide() == OrderSide.BUY && order.getPrice().compareTo(currentPrice) < 0) {
                // 买入限价低于当前价，暂不成交
                order.setStatus(OrderStatus.SUBMITTED);
                return;
            }
            if (order.getSide() == OrderSide.SELL && order.getPrice().compareTo(currentPrice) > 0) {
                // 卖出限价高于当前价，暂不成交
                order.setStatus(OrderStatus.SUBMITTED);
                return;
            }
            fillPrice = order.getPrice();
        } else {
            // MARKET 订单：以当前价成交
            fillPrice = currentPrice;
        }

        order.setFilledQuantity(order.getQuantity());
        order.setFilledPrice(fillPrice);

        // 计算手续费（与回测逻辑对齐: fee = fillPrice * quantity * feeRate）
        if (feeRate != null && feeRate.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal fee = fillPrice.multiply(order.getQuantity())
                    .multiply(feeRate)
                    .setScale(10, RoundingMode.HALF_UP);
            order.setFee(fee);
        } else {
            order.setFee(BigDecimal.ZERO);
        }

        order.setStatus(OrderStatus.SIMULATED);
        order.setTradeMode(ExecutionMode.PAPER);

        log.info("[Paper Trading] Order simulated: {} {} {} qty={} price={} fee={}",
                order.getSide(), order.getExchange(), order.getSymbol(),
                order.getQuantity(), fillPrice, order.getFee());
    }
}

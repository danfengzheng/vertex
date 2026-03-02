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

    /** 按周期从小到大排列，优先取最小周期的最新价格 */
    private static final KLineInterval[] PRICE_QUERY_INTERVALS = {
            KLineInterval.M1, KLineInterval.M3, KLineInterval.M5,
            KLineInterval.M15, KLineInterval.M30,
            KLineInterval.H1, KLineInterval.H2, KLineInterval.H4,
            KLineInterval.H6, KLineInterval.H8, KLineInterval.H12,
            KLineInterval.D1
    };

    /**
     * 获取当前价格（从 KLineStore 取最新 K线收盘价）
     * <p>
     * 按周期从小到大依次查询，返回第一个有数据的周期的最新收盘价。
     * 策略可能只订阅了特定周期（如 15m），因此需要遍历所有周期。
     */
    public BigDecimal getCurrentPrice(String exchange, String symbol) {
        long now = System.currentTimeMillis();
        for (KLineInterval interval : PRICE_QUERY_INTERVALS) {
            List<KLine> klines = klineStore.query(exchange, symbol, interval, null, null, 1, false);
            if (!klines.isEmpty()) {
                KLine latest = klines.get(0);
                // 跳过已过期的 bar（openTime 距今超过 2 个周期），避免订阅模式切换后
                // RocksDB 中残留的旧 M1 数据被优先命中，导致持仓价格永远冻结
                if (latest.getOpenTime() != null
                        && now - latest.getOpenTime() > 2 * interval.getMillis()) {
                    log.debug("[PriceCache] Skipping stale {} {} {} bar (age={}s)",
                            exchange, symbol, interval.getCode(),
                            (now - latest.getOpenTime()) / 1000);
                    continue;
                }
                return latest.getClose();
            }
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
            // MARKET 订单：使用信号价格成交（与回测一致，回测使用K线close价）
            // order.getPrice() 存放的是信号触发时的K线收盘价
            if (order.getPrice() != null && order.getPrice().compareTo(BigDecimal.ZERO) > 0) {
                fillPrice = order.getPrice();
            } else {
                fillPrice = currentPrice;
            }
        }

        order.setFilledPrice(fillPrice);

        // 计算手续费（与回测逻辑对齐: fee = fillPrice * quantity * feeRate）
        if (feeRate != null && feeRate.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal fee = fillPrice.multiply(order.getQuantity())
                    .multiply(feeRate)
                    .setScale(10, RoundingMode.HALF_UP);
            order.setFee(fee);

            // 买入时手续费从币数量中扣减（模拟实盘行为：交易所从买入的币中扣手续费）
            if (order.getSide() == OrderSide.BUY) {
                BigDecimal feeInQty = order.getQuantity().multiply(feeRate)
                        .setScale(10, RoundingMode.DOWN);
                order.setFilledQuantity(order.getQuantity().subtract(feeInQty));
            } else {
                order.setFilledQuantity(order.getQuantity());
            }
        } else {
            order.setFee(BigDecimal.ZERO);
            order.setFilledQuantity(order.getQuantity());
        }

        order.setStatus(OrderStatus.SIMULATED);
        order.setTradeMode(ExecutionMode.PAPER);

        log.info("[Paper Trading] Order simulated: {} {} {} qty={} actualQty={} price={} fee={}",
                order.getSide(), order.getExchange(), order.getSymbol(),
                order.getQuantity(), order.getFilledQuantity(), fillPrice, order.getFee());
    }
}

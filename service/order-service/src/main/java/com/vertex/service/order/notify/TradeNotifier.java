package com.vertex.service.order.notify;

import com.vertex.model.entity.strategy.Strategy;
import com.vertex.model.entity.trading.Order;

/**
 * 交易通知接口
 * <p>
 * 支持多种通知渠道（WebSocket、Telegram、飞书等），
 * 通过 CompositeTradeNotifier 聚合所有实现，实现统一分发。
 */
public interface TradeNotifier {

    /**
     * 通知类型标识
     */
    String type();

    /**
     * 通知订单创建（含 PENDING 和 SUBMITTED）
     *
     * @param order    订单
     * @param strategy 触发订单的策略
     */
    void notifyOrderCreated(Order order, Strategy strategy);

    /**
     * 通知订单成交或失败
     *
     * @param order 订单（含成交信息或错误信息）
     */
    void notifyOrderFilled(Order order);
}

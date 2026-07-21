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

    /**
     * 通知平仓关键失败事件（如超时、被拒、部分成交等需要用户立即介入的情况）。
     * <p>
     * 默认空实现；只有对交互式渠道（Telegram / 邮件等）有意义，
     * WebSocket 之类的通知无所谓，可以不覆写。
     * </p>
     *
     * @param order   触发告警的订单
     * @param stage   触发阶段：ATTEMPT_FAILED / RETRY / RECONCILED / FINAL_GIVEUP
     * @param message 用户可读的告警说明（含 attempt 次数、错误原因、下一步动作等）
     */
    default void notifyCloseFailure(Order order, String stage, String message) {
        // 默认空实现，非交互式渠道无需处理
    }
}

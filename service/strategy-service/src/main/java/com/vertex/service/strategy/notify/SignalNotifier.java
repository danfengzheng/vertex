package com.vertex.service.strategy.notify;

import com.vertex.model.entity.strategy.Signal;

/**
 * 策略信号通知器接口
 * <p>
 * 与 {@link com.vertex.service.order.notify.TradeNotifier}、
 * {@link com.vertex.service.chain.alert.AlertNotifier} 保持一致的设计：
 * 每个实现对应一种通知渠道（Telegram、Email 等），
 * 由 {@link CompositeSignalNotifier} 统一调度。
 * </p>
 */
public interface SignalNotifier {

    /**
     * 通知渠道标识，如 "telegram"
     */
    String type();

    /**
     * 发送信号通知
     *
     * @param signal 触发的交易信号
     */
    void notifySignal(Signal signal);
}

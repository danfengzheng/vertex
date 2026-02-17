package com.vertex.service.order.notify;

import com.vertex.model.entity.strategy.Strategy;
import com.vertex.model.entity.trading.Order;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 组合交易通知器
 * <p>
 * 聚合所有 TradeNotifier 实现，统一分发通知。
 * 任一通知渠道失败不影响其他渠道的通知。
 * Spring 会自动注入所有 TradeNotifier 实现到 notifiers 列表。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CompositeTradeNotifier {

    private final List<TradeNotifier> notifiers;

    /**
     * 通知订单创建
     */
    public void notifyOrderCreated(Order order, Strategy strategy) {
        for (TradeNotifier notifier : notifiers) {
            try {
                notifier.notifyOrderCreated(order, strategy);
            } catch (Exception e) {
                log.error("[CompositeTradeNotifier] Notifier [{}] failed for order created: {} {}",
                        notifier.type(), order.getExchange(), order.getSymbol(), e);
            }
        }
    }

    /**
     * 通知订单成交或失败
     */
    public void notifyOrderFilled(Order order) {
        for (TradeNotifier notifier : notifiers) {
            try {
                notifier.notifyOrderFilled(order);
            } catch (Exception e) {
                log.error("[CompositeTradeNotifier] Notifier [{}] failed for order filled: {} {}",
                        notifier.type(), order.getExchange(), order.getSymbol(), e);
            }
        }
    }

    /**
     * 获取已激活的通知渠道数量
     */
    public int activeCount() {
        return notifiers.size();
    }

    /**
     * 获取已激活的通知渠道类型列表
     */
    public List<String> activeTypes() {
        return notifiers.stream().map(TradeNotifier::type).toList();
    }
}

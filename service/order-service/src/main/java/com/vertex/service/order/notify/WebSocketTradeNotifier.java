package com.vertex.service.order.notify;

import com.vertex.model.entity.strategy.Strategy;
import com.vertex.model.entity.trading.Order;
import com.vertex.model.entity.trading.OrderStatus;
import com.vertex.model.vo.trading.OrderVO;
import com.vertex.service.order.websocket.TradePushService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * WebSocket 交易通知实现
 * <p>
 * 适配现有 TradePushService，将其纳入 TradeNotifier 体系。
 * 仅在 WebSocket 可用时激活。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnBean(SimpMessagingTemplate.class)
public class WebSocketTradeNotifier implements TradeNotifier {

    private final TradePushService tradePushService;

    @Override
    public String type() {
        return "websocket";
    }

    @Override
    public void notifyOrderCreated(Order order, Strategy strategy) {
        OrderVO vo = toVO(order);
        if (order.getStatus() == OrderStatus.PENDING) {
            tradePushService.pushPendingOrder(vo);
        } else {
            tradePushService.pushOrderUpdate(vo);
        }
    }

    @Override
    public void notifyOrderFilled(Order order) {
        tradePushService.pushOrderUpdate(toVO(order));
    }

    private OrderVO toVO(Order order) {
        return OrderVO.builder()
                .id(order.getId())
                .strategyId(order.getStrategyId())
                .accountId(order.getAccountId())
                .signalId(order.getSignalId())
                .exchange(order.getExchange())
                .symbol(order.getSymbol())
                .side(order.getSide())
                .orderType(order.getOrderType())
                .quantity(order.getQuantity())
                .price(order.getPrice())
                .filledQuantity(order.getFilledQuantity())
                .filledPrice(order.getFilledPrice())
                .fee(order.getFee())
                .status(order.getStatus())
                .tradeMode(order.getTradeMode())
                .exchangeOrderId(order.getExchangeOrderId())
                .errorMsg(order.getErrorMsg())
                .createTime(order.getCreateTime())
                .updateTime(order.getUpdateTime())
                .build();
    }
}

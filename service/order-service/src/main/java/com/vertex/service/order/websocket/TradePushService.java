package com.vertex.service.order.websocket;

import com.vertex.model.vo.trading.OrderVO;
import com.vertex.model.vo.trading.PositionVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

/**
 * 交易 WebSocket 推送服务
 * <p>
 * 当订单/持仓状态变化时，通过 STOMP 推送到订阅的前端客户端。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnBean(SimpMessagingTemplate.class)
public class TradePushService {

    private final SimpMessagingTemplate messagingTemplate;

    /**
     * 推送订单更新
     */
    public void pushOrderUpdate(OrderVO order) {
        try {
            messagingTemplate.convertAndSend("/topic/trade/order", order);
            log.debug("Order update pushed: {} {} {}", order.getId(), order.getStatus(), order.getSymbol());
        } catch (Exception e) {
            log.warn("Failed to push order update via WebSocket: {}", e.getMessage());
        }
    }

    /**
     * 推送持仓更新
     */
    public void pushPositionUpdate(PositionVO position) {
        try {
            messagingTemplate.convertAndSend("/topic/trade/position", position);
            log.debug("Position update pushed: {} {} {}", position.getId(), position.getStatus(), position.getSymbol());
        } catch (Exception e) {
            log.warn("Failed to push position update via WebSocket: {}", e.getMessage());
        }
    }

    /**
     * 推送待确认订单（MANUAL 模式）
     */
    public void pushPendingOrder(OrderVO order) {
        try {
            messagingTemplate.convertAndSend("/topic/trade/pending", order);
            log.info("Pending order notification pushed: {} {} {} {}",
                    order.getSide(), order.getExchange(), order.getSymbol(), order.getQuantity());
        } catch (Exception e) {
            log.warn("Failed to push pending order via WebSocket: {}", e.getMessage());
        }
    }
}

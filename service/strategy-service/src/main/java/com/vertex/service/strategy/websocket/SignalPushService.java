package com.vertex.service.strategy.websocket;

import com.alibaba.fastjson2.JSON;
import com.vertex.model.entity.strategy.Signal;
import com.vertex.model.vo.strategy.SignalVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 信号 WebSocket 推送服务
 * <p>
 * 当新信号生成时，通过 STOMP 推送到订阅的前端客户端。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnBean(SimpMessagingTemplate.class)
public class SignalPushService {

    private final SimpMessagingTemplate messagingTemplate;

    /**
     * 推送信号到所有订阅客户端
     */
    public void pushSignal(Signal signal) {
        try {
            SignalVO vo = convertToVO(signal);

            // 推送到全局信号主题
            messagingTemplate.convertAndSend("/topic/signal", vo);

            // 推送到交易对特定主题（便于前端按交易对过滤订阅）
            String symbolTopic = String.format("/topic/signal/%s/%s",
                    signal.getExchange(), signal.getSymbol().replace("-", ""));
            messagingTemplate.convertAndSend(symbolTopic, vo);

            log.debug("Signal pushed via WebSocket: {} {} {} {}",
                    signal.getSignalType(), signal.getExchange(), signal.getSymbol(), signal.getSignalStrength());
        } catch (Exception e) {
            log.warn("Failed to push signal via WebSocket: {}", e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private SignalVO convertToVO(Signal signal) {
        SignalVO vo = new SignalVO();
        vo.setId(signal.getId());
        vo.setStrategyId(signal.getStrategyId());
        vo.setStrategyName(signal.getStrategyName());
        vo.setSymbol(signal.getSymbol());
        vo.setExchange(signal.getExchange());
        vo.setInterval(signal.getInterval());
        vo.setSignalType(signal.getSignalType());
        vo.setSignalStrength(signal.getSignalStrength());
        vo.setPrice(signal.getPrice());
        vo.setSignalTime(signal.getSignalTime());
        vo.setDescription(signal.getDescription());

        // 解析指标 JSON
        if (signal.getIndicators() != null) {
            try {
                vo.setIndicators(JSON.parseObject(signal.getIndicators(), Map.class));
            } catch (Exception e) {
                log.warn("Failed to parse indicators JSON: {}", e.getMessage());
            }
        }

        return vo;
    }
}

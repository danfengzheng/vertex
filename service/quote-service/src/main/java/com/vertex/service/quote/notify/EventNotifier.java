package com.vertex.service.quote.notify;

import com.vertex.model.entity.quote.KLine;
import com.vertex.service.quote.event.KLineEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Spring Event 通知实现
 * <p>
 * 通过 Spring ApplicationEventPublisher 发布 KLineEvent，
 * 适用于同 JVM 内的模块间通信，默认启用。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "vertex.quote.notify.event", name = "enabled", havingValue = "true", matchIfMissing = true)
public class EventNotifier implements QuoteNotifier {

    private final ApplicationEventPublisher eventPublisher;

    @Override
    public String type() {
        return "event";
    }

    @Override
    public void notifyKLine(KLine kline) {
        try {
            eventPublisher.publishEvent(new KLineEvent(this, kline));
            log.debug("[Event] Published KLine event: {}:{}:{}", kline.getExchange(), kline.getSymbol(), kline.getInterval().getCode());
        } catch (Exception e) {
            log.error("[Event] Failed to publish KLine event: {}:{}:{}", kline.getExchange(), kline.getSymbol(), kline.getInterval().getCode(), e);
        }
    }

    @Override
    public void notifyKLineBatch(List<KLine> klines) {
        try {
            eventPublisher.publishEvent(new KLineEvent(this, klines));
            log.debug("[Event] Published batch KLine event, size: {}", klines.size());
        } catch (Exception e) {
            log.error("[Event] Failed to publish batch KLine event, size: {}", klines.size(), e);
        }
    }
}

package com.vertex.service.quote.notify;

import com.alibaba.fastjson2.JSON;
import com.vertex.model.entity.quote.KLine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * RocketMQ 通知实现
 * <p>
 * 将 KLine 数据以 JSON 格式发送到 RocketMQ，
 * topic 为配置项 vertex.quote.notify.rocketmq.topic（默认 KLINE_UPDATE），
 * tag 格式为 {exchange}_{symbol}_{interval}，便于消费端按需过滤。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "vertex.quote.notify.rocketmq", name = "enabled", havingValue = "true")
public class RocketMQNotifier implements QuoteNotifier {

    private final RocketMQTemplate rocketMQTemplate;

    @Value("${vertex.quote.notify.rocketmq.topic:KLINE_UPDATE}")
    private String topic;

    @Override
    public String type() {
        return "rocketmq";
    }

    @Override
    public void notifyKLine(KLine kline) {
        try {
            String tag = buildTag(kline);
            String destination = topic + ":" + tag;
            String payload = JSON.toJSONString(kline);

            rocketMQTemplate.send(destination, MessageBuilder.withPayload(payload).build());
            log.debug("[RocketMQ] Sent KLine to {}: {}:{}:{}", destination,
                    kline.getExchange(), kline.getSymbol(), kline.getInterval().getCode());
        } catch (Exception e) {
            log.error("[RocketMQ] Failed to send KLine: {}:{}:{}", kline.getExchange(),
                    kline.getSymbol(), kline.getInterval().getCode(), e);
        }
    }

    @Override
    public void notifyKLineBatch(List<KLine> klines) {
        for (KLine kline : klines) {
            notifyKLine(kline);
        }
    }

    /**
     * 构建 RocketMQ Tag
     * 格式: {exchange}_{symbol}_{interval}
     */
    private String buildTag(KLine kline) {
        return kline.getExchange() + "_" + kline.getSymbol() + "_" + kline.getInterval().getCode();
    }
}

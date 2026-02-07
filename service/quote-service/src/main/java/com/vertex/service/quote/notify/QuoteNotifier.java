package com.vertex.service.quote.notify;

import com.vertex.model.entity.quote.KLine;

import java.util.List;

/**
 * 行情通知接口
 * <p>
 * 支持多种通知渠道（Spring Event、RocketMQ、Kafka 等），
 * 通过 CompositeNotifier 聚合所有实现，实现统一分发。
 */
public interface QuoteNotifier {

    /**
     * 通知类型标识
     */
    String type();

    /**
     * 通知单条 K线更新
     */
    void notifyKLine(KLine kline);

    /**
     * 批量通知 K线更新
     */
    void notifyKLineBatch(List<KLine> klines);
}

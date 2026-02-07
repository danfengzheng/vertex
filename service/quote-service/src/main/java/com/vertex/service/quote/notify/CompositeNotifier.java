package com.vertex.service.quote.notify;

import com.vertex.model.entity.quote.KLine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 组合通知器
 * <p>
 * 聚合所有 QuoteNotifier 实现，统一分发通知。
 * 任一通知渠道失败不影响其他渠道的通知。
 * Spring 会自动注入所有 QuoteNotifier 实现到 notifiers 列表。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CompositeNotifier {

    private final List<QuoteNotifier> notifiers;

    /**
     * 通知单条 K线更新
     */
    public void notifyKLine(KLine kline) {
        for (QuoteNotifier notifier : notifiers) {
            try {
                notifier.notifyKLine(kline);
            } catch (Exception e) {
                log.error("[CompositeNotifier] Notifier [{}] failed for KLine: {}:{}:{}",
                        notifier.type(), kline.getExchange(), kline.getSymbol(),
                        kline.getInterval().getCode(), e);
            }
        }
    }

    /**
     * 批量通知 K线更新
     */
    public void notifyKLineBatch(List<KLine> klines) {
        for (QuoteNotifier notifier : notifiers) {
            try {
                notifier.notifyKLineBatch(klines);
            } catch (Exception e) {
                log.error("[CompositeNotifier] Notifier [{}] failed for batch KLine, size: {}",
                        notifier.type(), klines.size(), e);
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
        return notifiers.stream().map(QuoteNotifier::type).toList();
    }
}

package com.vertex.service.quote.notify;

import com.vertex.model.entity.quote.KLine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 组合通知器
 * <p>
 * 聚合所有 QuoteNotifier 实现，统一分发通知。
 * 任一通知渠道失败不影响其他渠道的通知。
 * Spring 会自动注入所有 QuoteNotifier 实现到 notifiers 列表。
 * <p>
 * 支持 {@link KLineNotifyGate} 门控：WS 重连期间对指定 key 的通知进入缓冲队列，
 * REST 补充数据完成后由门控统一排空，避免策略以不完整数据计算信号。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CompositeNotifier {

    private final List<QuoteNotifier> notifiers;

    /**
     * 可选门控组件：WS 重连补数据期间拦截通知，防止策略以不完整数据触发。
     * required = false 保持对未引入 gate 场景的向后兼容。
     */
    @Autowired(required = false)
    private KLineNotifyGate notifyGate;

    /**
     * 通知单条 K线更新。
     * <p>
     * 若当前 K 线对应的 (exchange:symbol:interval) 处于门控状态，通知将被暂存至
     * 缓冲队列；门控释放后由 {@link KLineNotifyGate#releaseAndDrain} 统一分发。
     */
    public void notifyKLine(KLine kline) {
        // 门控检查：WS 重连补数据期间暂存通知，待 REST 补充完成后再推送
        if (notifyGate != null && notifyGate.tryBuffer(kline)) {
            return;
        }
        doDispatch(kline);
    }

    /**
     * 直接分发，不经过门控检查（供 gate.releaseAndDrain 内部调用时使用，
     * 此时 gate 已移除，notifyKLine 与 doDispatch 效果相同）。
     */
    private void doDispatch(KLine kline) {
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

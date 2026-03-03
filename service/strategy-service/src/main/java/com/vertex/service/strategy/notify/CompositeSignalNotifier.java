package com.vertex.service.strategy.notify;

import com.vertex.model.entity.strategy.Signal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 组合策略信号通知器
 * <p>
 * 聚合所有 {@link SignalNotifier} 实现，统一分发信号通知。
 * 任一渠道失败不影响其他渠道的通知。
 * Spring 会自动注入所有 SignalNotifier 实现到 notifiers 列表，
 * 若无任何实现被激活则注入空列表，不影响服务启动。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CompositeSignalNotifier {

    private final List<SignalNotifier> notifiers;

    /**
     * 分发信号通知到所有已激活的通知渠道
     */
    public void notifySignal(Signal signal) {
        for (SignalNotifier notifier : notifiers) {
            try {
                notifier.notifySignal(signal);
            } catch (Exception e) {
                log.error("[CompositeSignalNotifier] Notifier [{}] failed for signal {}/{}",
                        notifier.type(), signal.getExchange(), signal.getSymbol(), e);
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
        return notifiers.stream().map(SignalNotifier::type).toList();
    }
}

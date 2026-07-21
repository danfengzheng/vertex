package com.vertex.service.order.notify;

import com.vertex.model.entity.strategy.Strategy;
import com.vertex.model.entity.trading.Order;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

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
     * 平仓失败告警用的独立线程池。
     * fire-and-forget，绝不阻塞主平仓重试流程；即使 Telegram 打不出去，
     * 也不影响后续的对账 + retry。
     */
    private ExecutorService alertExecutor;

    @PostConstruct
    public void initExecutor() {
        ThreadFactory tf = new ThreadFactory() {
            private final AtomicInteger n = new AtomicInteger();
            @Override public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "close-alert-" + n.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        };
        this.alertExecutor = Executors.newFixedThreadPool(2, tf);
    }

    @PreDestroy
    public void shutdownExecutor() {
        if (alertExecutor != null) alertExecutor.shutdown();
    }

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
     * 异步推送「平仓失败 / 需人工介入」告警。
     * <p>
     * fire-and-forget：立即返回，实际发送在独立线程池执行；
     * 任一渠道失败不阻塞主流程也不影响其它渠道。用于「先告警，再重试」的场景：
     * 第 1 次平仓失败立刻通知用户，用户可以在 Vertex 自动重试的同时手动介入。
     * </p>
     *
     * @param order   触发告警的订单
     * @param stage   告警阶段（ATTEMPT_FAILED / RECONCILED / FINAL_GIVEUP 等）
     * @param message 用户可读的说明
     */
    public void notifyCloseFailureAsync(Order order, String stage, String message) {
        if (alertExecutor == null || alertExecutor.isShutdown()) {
            // fallback：同步执行，避免告警丢失
            dispatchCloseFailure(order, stage, message);
            return;
        }
        try {
            alertExecutor.submit(() -> dispatchCloseFailure(order, stage, message));
        } catch (Exception e) {
            log.warn("[CompositeTradeNotifier] async submit close-failure alert failed: {}", e.getMessage());
        }
    }

    private void dispatchCloseFailure(Order order, String stage, String message) {
        for (TradeNotifier notifier : notifiers) {
            try {
                notifier.notifyCloseFailure(order, stage, message);
            } catch (Exception e) {
                log.error("[CompositeTradeNotifier] Notifier [{}] failed for close failure alert: {} {}",
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

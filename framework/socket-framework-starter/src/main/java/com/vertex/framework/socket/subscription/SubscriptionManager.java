package com.vertex.framework.socket.subscription;

import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 订阅管理器
 * <p>
 * 维护 topic → listeners 的映射关系，支持订阅、取消订阅、消息分发
 */
@Slf4j
public class SubscriptionManager {

    /** topic → 订阅列表 */
    private final Map<String, List<Subscription>> subscriptions = new ConcurrentHashMap<>();

    /**
     * 订阅主题
     */
    public Subscription subscribe(String topic, SubscriptionListener listener) {
        return subscribe(topic, null, listener);
    }

    /**
     * 带参数订阅主题
     */
    public Subscription subscribe(String topic, Map<String, String> params, SubscriptionListener listener) {
        Subscription subscription = Subscription.builder()
                .topic(topic)
                .params(params)
                .listener(listener)
                .active(true)
                .build();

        subscriptions.computeIfAbsent(topic, k -> new CopyOnWriteArrayList<>()).add(subscription);
        log.info("Subscribed to topic: {}, total subscriptions for topic: {}",
                topic, subscriptions.get(topic).size());
        return subscription;
    }

    /**
     * 取消订阅
     */
    public void unsubscribe(Subscription subscription) {
        subscription.setActive(false);
        List<Subscription> subs = subscriptions.get(subscription.getTopic());
        if (subs != null) {
            subs.remove(subscription);
            if (subs.isEmpty()) {
                subscriptions.remove(subscription.getTopic());
            }
        }
        log.info("Unsubscribed from topic: {}", subscription.getTopic());
    }

    /**
     * 取消某个 topic 的所有订阅
     */
    public void unsubscribeAll(String topic) {
        List<Subscription> removed = subscriptions.remove(topic);
        if (removed != null) {
            removed.forEach(s -> s.setActive(false));
            log.info("Unsubscribed all from topic: {}, count: {}", topic, removed.size());
        }
    }

    /**
     * 分发消息到对应 topic 的所有订阅者
     */
    public void dispatch(String topic, String payload) {
        List<Subscription> subs = subscriptions.get(topic);
        if (subs == null || subs.isEmpty()) {
            log.debug("No subscribers for topic: {}", topic);
            return;
        }

        for (Subscription sub : subs) {
            if (sub.isActive() && sub.getListener() != null) {
                try {
                    sub.getListener().onData(topic, payload);
                } catch (Exception e) {
                    log.error("Error dispatching message to subscriber, topic: {}", topic, e);
                }
            }
        }
    }

    /**
     * 获取所有已订阅的 topic
     */
    public Set<String> getSubscribedTopics() {
        return Collections.unmodifiableSet(subscriptions.keySet());
    }

    /**
     * 获取指定 topic 的订阅数量
     */
    public int getSubscriptionCount(String topic) {
        List<Subscription> subs = subscriptions.get(topic);
        return subs != null ? subs.size() : 0;
    }

    /**
     * 清除所有订阅
     */
    public void clear() {
        subscriptions.values().forEach(subs -> subs.forEach(s -> s.setActive(false)));
        subscriptions.clear();
        log.info("All subscriptions cleared");
    }
}

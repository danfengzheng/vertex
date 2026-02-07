package com.vertex.framework.socket.subscription;

/**
 * 订阅数据回调接口
 */
@FunctionalInterface
public interface SubscriptionListener {

    /**
     * 收到订阅数据
     *
     * @param topic   主题
     * @param payload 数据负载
     */
    void onData(String topic, String payload);
}

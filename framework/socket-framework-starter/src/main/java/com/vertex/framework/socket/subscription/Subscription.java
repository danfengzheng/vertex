package com.vertex.framework.socket.subscription;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 订阅信息模型
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Subscription {

    /** 订阅主题 */
    private String topic;

    /** 订阅参数 */
    private Map<String, String> params;

    /** 数据回调 */
    private transient SubscriptionListener listener;

    /** 是否活跃 */
    @Builder.Default
    private boolean active = true;
}

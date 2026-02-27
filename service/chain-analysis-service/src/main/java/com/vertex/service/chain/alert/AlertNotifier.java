package com.vertex.service.chain.alert;

import com.vertex.model.entity.chain.AlertRule;
import com.vertex.model.entity.chain.ChainToken;
import com.vertex.model.entity.chain.ChainTokenMetrics;

/**
 * 告警通知器接口
 * <p>
 * 每个实现对应一种通知渠道（Telegram、Email 等）。
 * 通过 {@link ChainAlertService} 统一调度。
 */
public interface AlertNotifier {

    /**
     * 通知渠道标识，e.g. "telegram"
     */
    String channel();

    /**
     * 是否可用（配置是否完整）
     */
    boolean isEnabled();

    /**
     * 发送新币告警通知
     *
     * @param token   代币信息
     * @param metrics 最新指标快照
     * @param rule    触发的告警规则
     */
    void sendAlert(ChainToken token, ChainTokenMetrics metrics, AlertRule rule);
}

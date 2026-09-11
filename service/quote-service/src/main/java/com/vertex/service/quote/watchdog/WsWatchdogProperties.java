package com.vertex.service.quote.watchdog;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * WS 连接看门狗配置。
 * <p>
 * yaml 前缀 {@code vertex.quote.ws-watchdog}。
 * 用于在"WS 断连但没有任何日志"或"reconnect 静默失败"场景下强制重连 + 告警，
 * 是 2026-09-03 线上事故（5h 断连未发现）的根治方案。
 * </p>
 */
@Data
@ConfigurationProperties(prefix = "vertex.quote.ws-watchdog")
public class WsWatchdogProperties {

    /** 是否启用。默认开（生产建议永远开）*/
    private boolean enabled = true;

    /** 检查间隔秒。默认 30s。此值仅用于告警文案；实际调度间隔由 checkIntervalMs 控制（Spring @Scheduled 限制） */
    private int checkIntervalSec = 30;

    /** 实际调度间隔（毫秒）—— @Scheduled(fixedDelayString) 从此值读 */
    private long checkIntervalMs = 30_000L;

    /** 距上次收到消息 > 此值 → 强制重连 + WARN 日志。默认 90s（3 倍心跳周期）*/
    private int noMessageWarnSec = 90;

    /** 距上次收到消息 > 此值 → Telegram 告警。默认 180s */
    private int noMessageAlertSec = 180;

    /** 距上次收到消息 > 此值 → 二次告警（"仍未恢复"）。默认 600s */
    private int noMessageSecondaryAlertSec = 600;

    /** 两次告警之间最短间隔，防轰炸。默认 300s。 */
    private int alertRateLimitSec = 300;

    /** Telegram 告警 —— 独立配置或留空 fallback 到 vertex.strategy.telegram */
    private String telegramBotToken;
    private String telegramChatId;
    private String telegramApiUrl = "https://api.telegram.org";
}

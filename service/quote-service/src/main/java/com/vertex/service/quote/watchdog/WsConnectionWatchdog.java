package com.vertex.service.quote.watchdog;

import com.vertex.framework.socket.exchange.ExchangeWebSocketClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * WS 连接看门狗 —— 2026-09-03 线上事故（5h 断连无告警）的核心防线。
 * <p>
 * 每 30 秒扫描所有 {@link ExchangeWebSocketClient} bean：
 * <ul>
 *   <li>{@code lastMessageAt} 距今 > 90s → 强制 reconnect + 记录 WARN 日志</li>
 *   <li>距今 > 180s → Telegram 首次告警</li>
 *   <li>距今 > 600s → Telegram 二次告警（"仍未恢复"）</li>
 *   <li>恢复后自动发"已恢复"告警</li>
 * </ul>
 * 告警去抖：同一 client 两次告警最短 {@code alertRateLimitSec} 间隔。
 * </p>
 * <p>
 * 独立于 handler pipeline 事件流 —— 即使 channelInactive / exceptionCaught 全部失效，
 * 也能靠"长时无数据"这个绝对信号发现问题。
 * </p>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "vertex.quote.ws-watchdog", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(WsWatchdogProperties.class)
@RequiredArgsConstructor
public class WsConnectionWatchdog {

    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final WsWatchdogProperties props;
    private final ApplicationContext appCtx;
    private final TelegramAlerter alerter;

    /** clientBeanName → 上一次告警时间戳 */
    private final Map<String, Long> lastAlertMs = new HashMap<>();
    /** clientBeanName → 是否处于"待恢复"告警状态 */
    private final Map<String, Boolean> awaitingRecoveryAlert = new HashMap<>();
    /** clientBeanName → 上次告警级别（1=WARN，2=SECONDARY，方便判断是否升级）*/
    private final Map<String, Integer> lastAlertLevel = new HashMap<>();

    /**
     * 检查间隔硬编码 30s（属性 checkIntervalSec 仅用于告警文案 + 未来动态支持）。
     * Spring @Scheduled 不支持从 @ConfigurationProperties 里的 int 计算 fixedDelay 表达式，
     * 用固定值最简单稳定。若需自定义，重启前改这里的常量。
     */
    @Scheduled(fixedDelayString = "${vertex.quote.ws-watchdog.check-interval-ms:30000}",
               initialDelay = 30_000L)
    public void tick() {
        Map<String, ExchangeWebSocketClient> clients = appCtx.getBeansOfType(ExchangeWebSocketClient.class);
        if (clients.isEmpty()) return;

        long now = System.currentTimeMillis();
        for (var entry : clients.entrySet()) {
            String beanName = entry.getKey();
            ExchangeWebSocketClient client = entry.getValue();
            try {
                checkOne(beanName, client, now);
            } catch (Exception ex) {
                log.warn("[WsWatchdog] check bean={} error: {}", beanName, ex.getMessage());
            }
        }
    }

    private void checkOne(String beanName, ExchangeWebSocketClient client, long now) {
        long silentMs = client.getMillisSinceLastMessage();
        String label = client.getExchangeConfig() == null ? beanName
                : client.getExchangeConfig().getExchangeType().getCode() + "@" + beanName;

        // silentMs < 0 表示尚未初始化，跳过
        if (silentMs < 0) return;

        long silentSec = silentMs / 1000;
        long warnThreshold = props.getNoMessageWarnSec();
        long alertThreshold = props.getNoMessageAlertSec();
        long secondaryThreshold = props.getNoMessageSecondaryAlertSec();

        // ── 恢复通知 ─────────────────────────────
        if (silentSec < warnThreshold && Boolean.TRUE.equals(awaitingRecoveryAlert.get(beanName))) {
            String msg = "✅ <b>WS 已恢复</b>\n" +
                    "Client: " + label + "\n" +
                    "Time: " + LocalDateTime.now().format(TS_FMT);
            alerter.alert(msg);
            log.info("[WsWatchdog] {} recovered", label);
            awaitingRecoveryAlert.remove(beanName);
            lastAlertLevel.remove(beanName);
            lastAlertMs.remove(beanName);
            return;
        }

        // ── 一切正常 ─────────────────────────────
        if (silentSec < warnThreshold) {
            return;
        }

        // ── WARN：强制重连（日志级别）─────────────
        if (silentSec >= warnThreshold && silentSec < alertThreshold) {
            log.warn("[WsWatchdog] {} silent {}s > warn threshold {}s. Forcing reconnect...",
                    label, silentSec, warnThreshold);
            tryForceReconnect(client, label);
            return;
        }

        // ── 告警节流 + 分级 ────────────────────────
        int currentLevel = silentSec >= secondaryThreshold ? 2 : 1;
        Integer lastLevel = lastAlertLevel.get(beanName);
        long sinceLastAlert = now - lastAlertMs.getOrDefault(beanName, 0L);

        // 只有：① 首次告警 ② 升级到 SECONDARY ③ 超过 rateLimit ── 才再发
        boolean shouldAlert = lastLevel == null
                || (currentLevel > lastLevel)
                || sinceLastAlert > props.getAlertRateLimitSec() * 1000L;

        if (shouldAlert) {
            String prefix = currentLevel == 2 ? "🚨 <b>WS 长时无数据（严重）</b>" : "⚠️ <b>WS 无数据告警</b>";
            String msg = prefix + "\n"
                    + "Client: " + label + "\n"
                    + "静默时长: " + silentSec + "s\n"
                    + "已强制重连（每 " + props.getCheckIntervalSec() + "s 一次），仍未恢复\n"
                    + "Time: " + LocalDateTime.now().format(TS_FMT);
            alerter.alert(msg);
            log.error("[WsWatchdog] ALERT sent for {} (silent {}s, level={})", label, silentSec, currentLevel);
            lastAlertMs.put(beanName, now);
            lastAlertLevel.put(beanName, currentLevel);
            awaitingRecoveryAlert.put(beanName, true);
        }

        // 无论如何都持续尝试重连
        tryForceReconnect(client, label);
    }

    private void tryForceReconnect(ExchangeWebSocketClient client, String label) {
        try {
            client.forceReconnect();
        } catch (Exception ex) {
            log.warn("[WsWatchdog] {} forceReconnect failed: {}", label, ex.getMessage());
        }
    }
}

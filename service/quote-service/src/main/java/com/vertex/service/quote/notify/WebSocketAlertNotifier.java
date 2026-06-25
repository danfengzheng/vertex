package com.vertex.service.quote.notify;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * WebSocket 连接告警通知器（Telegram）。
 * <p>
 * 在交易所 WebSocket 行情通道<b>断开</b>与<b>重连成功</b>时发送 Telegram 告警，
 * 复用 {@code vertex.strategy.telegram.*} 同一组配置（同一个 Bot Token 与 Chat ID），
 * 与信号通知共用同一个 Telegram 通道。
 * </p>
 * <p>
 * 仅在 {@code vertex.strategy.telegram.enabled=true} 时激活，未启用时本组件不注册，
 * BinanceWsDataSource 中以 {@code @Autowired(required=false)} 注入实现安全降级。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "vertex.strategy.telegram", name = "enabled", havingValue = "true")
public class WebSocketAlertNotifier {

    private static final MediaType JSON_MEDIA = MediaType.parse("application/json; charset=utf-8");
    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.of("UTC"));

    private final OkHttpClient quoteOkHttpClient;

    @Value("${vertex.strategy.telegram.botToken:}")
    private String botToken;

    @Value("${vertex.strategy.telegram.chatId:}")
    private String chatId;

    @Value("${vertex.strategy.telegram.apiUrl:https://api.telegram.org}")
    private String apiUrl;

    @PostConstruct
    public void init() {
        log.info("[WebSocketAlertNotifier] WebSocket Telegram alerts activated, chatId={}, apiUrl={}",
                chatId, apiUrl);
    }

    /**
     * 发送 WebSocket 断开告警。
     *
     * @param exchange 交易所代码（如 "binance"）
     * @param wsUrl    WebSocket URL（用于排查）
     * @param reason   断开原因（可空）
     */
    public void notifyDisconnected(String exchange, String wsUrl, String reason) {
        String message = String.format(
                "🔴 <b>行情通道断开</b>\n" +
                "交易所: <code>%s</code>\n" +
                "URL: <code>%s</code>\n" +
                "原因: %s\n" +
                "时间: <code>%s UTC</code>",
                escapeHtml(exchange == null ? "unknown" : exchange),
                escapeHtml(wsUrl == null ? "n/a" : wsUrl),
                escapeHtml(StringUtils.hasText(reason) ? reason : "channel closed"),
                TIME_FMT.format(Instant.now())
        );
        sendMessage(message);
    }

    /**
     * 发送 WebSocket 重连成功告警。
     *
     * @param exchange 交易所代码
     * @param wsUrl    WebSocket URL
     * @param downtimeMs 断连持续时长（毫秒）；-1 表示未知
     */
    public void notifyReconnected(String exchange, String wsUrl, long downtimeMs) {
        String downtime = downtimeMs >= 0
                ? formatDuration(downtimeMs)
                : "n/a";
        String message = String.format(
                "🟢 <b>行情通道已恢复</b>\n" +
                "交易所: <code>%s</code>\n" +
                "URL: <code>%s</code>\n" +
                "断连时长: <code>%s</code>\n" +
                "时间: <code>%s UTC</code>",
                escapeHtml(exchange == null ? "unknown" : exchange),
                escapeHtml(wsUrl == null ? "n/a" : wsUrl),
                downtime,
                TIME_FMT.format(Instant.now())
        );
        sendMessage(message);
    }

    private void sendMessage(String text) {
        if (!StringUtils.hasText(botToken) || !StringUtils.hasText(chatId)) {
            log.warn("[WebSocketAlertNotifier] Bot token or chat ID not configured, skipping alert");
            return;
        }

        String url = String.format("%s/bot%s/sendMessage", apiUrl, botToken);
        String jsonBody = String.format(
                "{\"chat_id\":\"%s\",\"text\":\"%s\",\"parse_mode\":\"HTML\"}",
                chatId,
                escapeJson(text)
        );

        Request request = new Request.Builder()
                .url(url)
                .post(RequestBody.create(jsonBody, JSON_MEDIA))
                .build();

        try (Response response = quoteOkHttpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String body = response.body() != null ? response.body().string() : "";
                log.warn("[WebSocketAlertNotifier] sendMessage failed, status={}, body={}",
                        response.code(), body);
            } else {
                log.info("[WebSocketAlertNotifier] Alert sent to chatId={}", chatId);
            }
        } catch (Exception e) {
            log.warn("[WebSocketAlertNotifier] Failed to send alert: {}", e.getMessage());
        }
    }

    private static String formatDuration(long ms) {
        if (ms < 1000) return ms + "ms";
        long s = ms / 1000;
        if (s < 60) return s + "s";
        long m = s / 60;
        long secs = s % 60;
        if (m < 60) return m + "m " + secs + "s";
        long h = m / 60;
        long mins = m % 60;
        return h + "h " + mins + "m " + secs + "s";
    }

    /** 转义 HTML 特殊字符，防止 Telegram HTML 解析异常 */
    private static String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    /** 转义 JSON 字符串中的特殊字符 */
    private static String escapeJson(String text) {
        return text.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "");
    }
}

package com.vertex.service.quote.watchdog;

import com.alibaba.fastjson2.JSONObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 独立的 Telegram 告警发送器（quote-service 内），不依赖 order-service。
 * <p>
 * 配置源：
 * <ul>
 *   <li>{@code vertex.quote.ws-watchdog.telegram-bot-token}（优先）</li>
 *   <li>{@code vertex.strategy.telegram.bot-token}（fallback）</li>
 * </ul>
 * 两者都为空则 disabled（只打日志）。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TelegramAlerter {

    private static final MediaType JSON = MediaType.parse("application/json");

    private final WsWatchdogProperties watchdogProps;

    @Value("${vertex.strategy.telegram.bot-token:}")
    private String strategyBotToken;
    @Value("${vertex.strategy.telegram.chat-id:}")
    private String strategyChatId;
    @Value("${vertex.strategy.telegram.api-url:https://api.telegram.org}")
    private String strategyApiUrl;

    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build();

    public boolean isEnabled() {
        return effectiveBotToken() != null && !effectiveBotToken().isBlank()
                && effectiveChatId() != null && !effectiveChatId().isBlank();
    }

    public void alert(String message) {
        if (!isEnabled()) {
            log.warn("[TelegramAlerter] disabled (no bot-token/chat-id). Message: {}", message);
            return;
        }
        String botToken = effectiveBotToken();
        String chatId = effectiveChatId();
        String apiUrl = effectiveApiUrl();

        JSONObject body = new JSONObject();
        body.put("chat_id", chatId);
        body.put("text", message);
        body.put("parse_mode", "HTML");

        Request req = new Request.Builder()
                .url(apiUrl + "/bot" + botToken + "/sendMessage")
                .post(RequestBody.create(body.toJSONString(), JSON))
                .build();

        try (Response resp = http.newCall(req).execute()) {
            if (!resp.isSuccessful()) {
                log.warn("[TelegramAlerter] send failed HTTP {}: {}", resp.code(),
                        resp.body() == null ? "" : resp.body().string());
            }
        } catch (Exception e) {
            log.warn("[TelegramAlerter] send exception: {}", e.getMessage());
        }
    }

    private String effectiveBotToken() {
        return watchdogProps.getTelegramBotToken() != null && !watchdogProps.getTelegramBotToken().isBlank()
                ? watchdogProps.getTelegramBotToken() : strategyBotToken;
    }
    private String effectiveChatId() {
        return watchdogProps.getTelegramChatId() != null && !watchdogProps.getTelegramChatId().isBlank()
                ? watchdogProps.getTelegramChatId() : strategyChatId;
    }
    private String effectiveApiUrl() {
        return watchdogProps.getTelegramApiUrl() != null && !watchdogProps.getTelegramApiUrl().isBlank()
                ? watchdogProps.getTelegramApiUrl() : strategyApiUrl;
    }
}

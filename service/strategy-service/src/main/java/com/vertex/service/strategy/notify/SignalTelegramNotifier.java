package com.vertex.service.strategy.notify;

import com.vertex.api.notify.INotifyConfigService;
import com.vertex.model.entity.strategy.Signal;
import com.vertex.model.vo.system.NotifyConfigVO;
import com.vertex.service.strategy.config.StrategyProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 策略信号 Telegram 通知器
 * <p>
 * 当策略生成 BUY/SELL 信号时，通过 Telegram Bot API 发送通知。
 * 仅在 vertex.strategy.telegram.enabled=true 时激活。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "vertex.strategy.telegram", name = "enabled", havingValue = "true")
public class SignalTelegramNotifier implements SignalNotifier {

    private static final MediaType JSON_MEDIA = MediaType.parse("application/json; charset=utf-8");
    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("UTC"));

    private final StrategyProperties properties;
    @Qualifier("chainOkHttpClient")
    private final OkHttpClient httpClient;

    /** 用户通知配置服务（可选注入，未部署 user-service 时降级为全局配置） */
    @Autowired(required = false)
    private INotifyConfigService notifyConfigService;

    @Override
    public String type() {
        return "telegram";
    }

    @Override
    public void notifySignal(Signal signal) {
        String emoji;
        switch (signal.getSignalType()) {
            case BUY -> emoji = "📈";
            case SELL -> emoji = "📉";
            default -> emoji = "ℹ️";
        }

        String signalTime = signal.getSignalTime() != null
                ? TIME_FMT.format(Instant.ofEpochMilli(signal.getSignalTime()))
                : "N/A";

        String message = String.format(
                "%s *信号通知*\n" +
                "策略: `%s`\n" +
                "方向: *%s*\n" +
                "交易对: `%s`\n" +
                "交易所: `%s`\n" +
                "周期: `%s`\n" +
                "触发价格: `%s`\n" +
                "信号强度: `%d`\n" +
                "时间: `%s UTC`\n" +
                "描述: %s",
                emoji,
                signal.getStrategyName() != null ? signal.getStrategyName() : "unknown",
                signal.getSignalType(),
                signal.getSymbol(),
                signal.getExchange(),
                signal.getInterval() != null ? signal.getInterval().getCode() : "N/A",
                signal.getPrice() != null ? signal.getPrice().stripTrailingZeros().toPlainString() : "N/A",
                signal.getSignalStrength() != null ? signal.getSignalStrength() : 0,
                signalTime,
                signal.getDescription() != null ? signal.getDescription() : ""
        );

        // 优先使用信号所有者配置的 chat_id，回退到全局配置
        String chatId = resolveUserChatId(signal.getCreateBy());
        sendMessage(message, chatId);
    }

    /**
     * 解析通知目标 chat_id：
     * 1. 如果信号有归属用户且该用户配置了 Telegram 通知 → 使用用户自定义 chat_id
     * 2. 否则回退到全局 application.yaml 中配置的 chat_id
     */
    private String resolveUserChatId(Long createBy) {
        if (createBy != null && notifyConfigService != null) {
            NotifyConfigVO userConfig = notifyConfigService.getByUserId(createBy, "TELEGRAM");
            if (userConfig != null && userConfig.getEnabled() != null && userConfig.getEnabled() == 1
                    && StringUtils.hasText(userConfig.getChatId())) {
                return userConfig.getChatId();
            }
        }
        return properties.getTelegram().getChatId();
    }

    private void sendMessage(String text, String chatId) {
        StrategyProperties.Telegram config = properties.getTelegram();
        if (!StringUtils.hasText(config.getBotToken()) || !StringUtils.hasText(chatId)) {
            log.warn("[Signal Telegram] Bot token or chat ID not configured, skipping notification");
            return;
        }

        String url = String.format("%s/bot%s/sendMessage", config.getApiUrl(), config.getBotToken());
        String jsonBody = String.format(
                "{\"chat_id\":\"%s\",\"text\":\"%s\",\"parse_mode\":\"Markdown\"}",
                chatId,
                escapeJson(text)
        );

        Request request = new Request.Builder()
                .url(url)
                .post(RequestBody.create(jsonBody, JSON_MEDIA))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String body = response.body() != null ? response.body().string() : "";
                log.warn("[Signal Telegram] sendMessage failed, code: {}, body: {}", response.code(), body);
            } else {
                log.debug("[Signal Telegram] Message sent successfully");
            }
        } catch (Exception e) {
            log.warn("[Signal Telegram] Failed to send message: {}", e.getMessage());
        }
    }

    private String escapeJson(String text) {
        return text.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n");
    }
}

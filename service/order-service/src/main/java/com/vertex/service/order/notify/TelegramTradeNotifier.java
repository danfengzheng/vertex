package com.vertex.service.order.notify;

import com.vertex.api.notify.INotifyConfigService;
import com.vertex.model.entity.strategy.Strategy;
import com.vertex.model.entity.trading.Order;
import com.vertex.model.entity.trading.OrderStatus;
import com.vertex.model.vo.system.NotifyConfigVO;
import com.vertex.service.order.config.TradingProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;

/**
 * Telegram 交易通知实现
 * <p>
 * 通过 Telegram Bot API 发送交易通知消息。
 * 仅在 vertex.trading.telegram.enabled=true 时激活。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "vertex.trading.telegram", name = "enabled", havingValue = "true")
public class TelegramTradeNotifier implements TradeNotifier {

    private final OkHttpClient httpClient;
    private final TradingProperties properties;

    /** 用户通知配置服务（可选注入，降级为全局配置） */
    @Autowired(required = false)
    private INotifyConfigService notifyConfigService;

    private static final MediaType JSON_MEDIA = MediaType.parse("application/json; charset=utf-8");

    @Override
    public String type() {
        return "telegram";
    }

    @Override
    public void notifyOrderCreated(Order order, Strategy strategy) {
        String statusLabel = order.getStatus() == OrderStatus.PENDING ? "待确认" : "已提交";
        String strategyName = strategy != null ? strategy.getName() : "unknown";

        String message = String.format(
                "\uD83D\uDCCB *订单创建*\n" +
                "方向: `%s`\n" +
                "交易对: `%s`\n" +
                "交易所: `%s`\n" +
                "数量: `%s`\n" +
                "类型: `%s`\n" +
                "策略: `%s`\n" +
                "状态: *%s*",
                order.getSide(),
                order.getSymbol(),
                order.getExchange(),
                formatQuantity(order.getQuantity()),
                order.getOrderType(),
                strategyName,
                statusLabel
        );

        sendMessage(message, order.getCreateBy());
    }

    @Override
    public void notifyOrderFilled(Order order) {
        String emoji;
        String title;
        if (order.getStatus() == OrderStatus.FILLED || order.getStatus() == OrderStatus.SIMULATED) {
            emoji = "\u2705";
            title = "订单成交";
        } else if (order.getStatus() == OrderStatus.REJECTED) {
            emoji = "\u274C";
            title = "订单失败";
        } else {
            emoji = "\u2139\uFE0F";
            title = "订单更新";
        }

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%s *%s*\n", emoji, title));
        sb.append(String.format("方向: `%s`\n", order.getSide()));
        sb.append(String.format("交易对: `%s`\n", order.getSymbol()));
        sb.append(String.format("交易所: `%s`\n", order.getExchange()));

        if (order.getFilledQuantity() != null && order.getFilledQuantity().compareTo(BigDecimal.ZERO) > 0) {
            sb.append(String.format("成交数量: `%s`\n", formatQuantity(order.getFilledQuantity())));
        }
        if (order.getFilledPrice() != null && order.getFilledPrice().compareTo(BigDecimal.ZERO) > 0) {
            sb.append(String.format("成交价格: `%s`\n", order.getFilledPrice().stripTrailingZeros().toPlainString()));
        }
        if (order.getFee() != null && order.getFee().compareTo(BigDecimal.ZERO) > 0) {
            sb.append(String.format("手续费: `%s`\n", order.getFee().stripTrailingZeros().toPlainString()));
        }
        if (order.getErrorMsg() != null && !order.getErrorMsg().isEmpty()) {
            sb.append(String.format("错误: `%s`\n", order.getErrorMsg()));
        }
        sb.append(String.format("模式: `%s`", order.getTradeMode()));

        sendMessage(sb.toString(), order.getCreateBy());
    }

    // ─── 内部方法 ───────────────────────────────────

    /**
     * 解析通知目标 chat_id：优先使用订单所有者配置，回退到全局配置
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

    private void sendMessage(String text, Long createBy) {
        TradingProperties.Telegram config = properties.getTelegram();
        String chatId = resolveUserChatId(createBy);
        if (!StringUtils.hasText(config.getBotToken()) || !StringUtils.hasText(chatId)) {
            log.warn("[Telegram] Bot token or chat ID not configured, skipping notification");
            return;
        }

        String url = String.format("%s/bot%s/sendMessage",
                config.getApiUrl(), config.getBotToken());

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
                log.warn("[Telegram] sendMessage failed, code: {}, body: {}", response.code(), body);
            } else {
                log.debug("[Telegram] Message sent successfully");
            }
        } catch (Exception e) {
            log.warn("[Telegram] Failed to send message: {}", e.getMessage());
        }
    }

    private String formatQuantity(BigDecimal qty) {
        return qty != null ? qty.stripTrailingZeros().toPlainString() : "0";
    }

    private String escapeJson(String text) {
        return text.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n");
    }
}

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

        // 使用 HTML 格式，避免策略名称含特殊字符导致 Telegram Markdown 解析失败
        String message = String.format(
                "\uD83D\uDCCB <b>订单创建</b>\n" +
                "方向: <code>%s</code>\n" +
                "交易对: <code>%s</code>\n" +
                "交易所: <code>%s</code>\n" +
                "数量: <code>%s</code>\n" +
                "类型: <code>%s</code>\n" +
                "策略: <code>%s</code>\n" +
                "状态: <b>%s</b>",
                order.getSide(),
                escapeHtml(order.getSymbol()),
                escapeHtml(order.getExchange()),
                formatQuantity(order.getQuantity()),
                order.getOrderType(),
                escapeHtml(strategyName),
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
        sb.append(String.format("%s <b>%s</b>\n", emoji, title));
        sb.append(String.format("方向: <code>%s</code>\n", order.getSide()));
        sb.append(String.format("交易对: <code>%s</code>\n", escapeHtml(order.getSymbol())));
        sb.append(String.format("交易所: <code>%s</code>\n", escapeHtml(order.getExchange())));

        if (order.getFilledQuantity() != null && order.getFilledQuantity().compareTo(BigDecimal.ZERO) > 0) {
            sb.append(String.format("成交数量: <code>%s</code>\n", formatQuantity(order.getFilledQuantity())));
        }
        if (order.getFilledPrice() != null && order.getFilledPrice().compareTo(BigDecimal.ZERO) > 0) {
            sb.append(String.format("成交价格: <code>%s</code>\n", order.getFilledPrice().stripTrailingZeros().toPlainString()));
        }
        if (order.getFee() != null && order.getFee().compareTo(BigDecimal.ZERO) > 0) {
            sb.append(String.format("手续费: <code>%s</code>\n", order.getFee().stripTrailingZeros().toPlainString()));
        }
        if (order.getErrorMsg() != null && !order.getErrorMsg().isEmpty()) {
            sb.append(String.format("错误: <code>%s</code>\n", escapeHtml(order.getErrorMsg())));
        }
        sb.append(String.format("模式: <code>%s</code>", order.getTradeMode()));

        sendMessage(sb.toString(), order.getCreateBy());
    }

    @Override
    public void notifyCloseFailure(Order order, String stage, String message) {
        String emoji = stageEmoji(stage);
        String title = stageTitle(stage);
        StringBuilder sb = new StringBuilder(256);
        sb.append(emoji).append(" <b>").append(title).append("</b>\n");
        sb.append("交易对: <code>").append(escapeHtml(order.getSymbol())).append("</code>\n");
        sb.append("交易所: <code>").append(escapeHtml(order.getExchange())).append("</code>\n");
        sb.append("方向: <code>").append(order.getSide()).append("</code>\n");
        if (order.getQuantity() != null) {
            sb.append("数量: <code>").append(formatQuantity(order.getQuantity())).append("</code>\n");
        }
        if (order.getId() != null) {
            sb.append("订单ID: <code>").append(order.getId()).append("</code>\n");
        }
        sb.append("说明: ").append(escapeHtml(message));
        sendMessage(sb.toString(), order.getCreateBy());
    }

    private static String stageEmoji(String stage) {
        if (stage == null) return "⚠️";
        return switch (stage) {
            case "ATTEMPT_FAILED" -> "⚠️";
            case "RECONCILED" -> "✅";
            case "FINAL_GIVEUP" -> "🚨";
            default -> "⚠️";
        };
    }

    private static String stageTitle(String stage) {
        if (stage == null) return "平仓告警";
        return switch (stage) {
            case "ATTEMPT_FAILED" -> "平仓失败（正在自动重试）";
            case "RECONCILED" -> "平仓已完成（对账确认）";
            case "FINAL_GIVEUP" -> "平仓最终失败 - 需人工介入";
            default -> "平仓告警";
        };
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
            log.warn("[Trade Telegram] Bot token or chat ID not configured, skipping notification");
            return;
        }

        String url = String.format("%s/bot%s/sendMessage",
                config.getApiUrl(), config.getBotToken());

        String jsonBody = String.format(
                "{\"chat_id\":\"%s\",\"text\":\"%s\",\"parse_mode\":\"HTML\"}",
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
                log.warn("[Trade Telegram] sendMessage failed, status={}, body={}", response.code(), body);
            } else {
                log.debug("[Trade Telegram] Message sent successfully");
            }
        } catch (Exception e) {
            log.warn("[Trade Telegram] Failed to send message: {}", e.getMessage());
        }
    }

    private String formatQuantity(BigDecimal qty) {
        return qty != null ? qty.stripTrailingZeros().toPlainString() : "0";
    }

    /** 转义 HTML 特殊字符，防止 Telegram HTML 解析异常 */
    private String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    /** 转义 JSON 字符串中的特殊字符 */
    private String escapeJson(String text) {
        return text.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "");
    }
}

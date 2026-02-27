package com.vertex.service.chain.alert.impl;

import com.vertex.model.entity.chain.AlertRule;
import com.vertex.model.entity.chain.ChainToken;
import com.vertex.model.entity.chain.ChainTokenMetrics;
import com.vertex.service.chain.alert.AlertNotifier;
import com.vertex.service.chain.config.ChainAnalysisProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Telegram 链上新币告警通知器
 * <p>
 * 复用现有 Telegram Bot Token，向配置的 chat_id 发送 Markdown 格式告警消息。
 * 仅在 {@code vertex.chain.telegram.enabled=true} 时激活。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "vertex.chain.telegram", name = "enabled", havingValue = "true")
public class TelegramChainAlertNotifier implements AlertNotifier {

    private static final MediaType JSON_MEDIA = MediaType.parse("application/json; charset=utf-8");

    private final ChainAnalysisProperties properties;
    @Qualifier("chainOkHttpClient")
    private final OkHttpClient httpClient;

    @Override
    public String channel() {
        return "telegram";
    }

    @Override
    public boolean isEnabled() {
        ChainAnalysisProperties.Telegram cfg = properties.getTelegram();
        return cfg.isEnabled()
                && cfg.getBotToken() != null && !cfg.getBotToken().isBlank()
                && cfg.getChatId() != null && !cfg.getChatId().isBlank();
    }

    @Override
    public void sendAlert(ChainToken token, ChainTokenMetrics metrics, AlertRule rule) {
        if (!isEnabled()) return;

        String message = buildMessage(token, metrics, rule);
        sendTelegramMessage(message);
    }

    // ─── 内部方法 ──────────────────────────────────────────

    private String buildMessage(ChainToken token, ChainTokenMetrics metrics, AlertRule rule) {
        StringBuilder sb = new StringBuilder();
        sb.append("🚨 *新币告警* | ").append(token.getChain()).append("\n\n");

        sb.append("📌 *").append(escapeMarkdown(token.getSymbol())).append("*");
        if (token.getName() != null) {
            sb.append(" — ").append(escapeMarkdown(token.getName()));
        }
        sb.append("\n");

        // 评分
        sb.append(String.format("⭐ 综合评分: `%d` (%s)\n",
                token.getScore(), gradeEmoji(token.getScore())));
        sb.append(String.format("   链上:%d  市场:%d  代币经济:%d  新颖:%d\n",
                token.getScoreOnchain(), token.getScoreMarket(),
                token.getScoreTokenomics(), token.getScoreNovelty()));

        // 市场数据
        if (metrics != null) {
            if (metrics.getPriceUsd() != null) {
                sb.append(String.format("💰 价格: `$%s`\n", formatDecimal(metrics.getPriceUsd(), 8)));
            }
            if (metrics.getLiquidityUsd() != null) {
                sb.append(String.format("💧 流动性: `$%s`\n", formatLarge(metrics.getLiquidityUsd())));
            }
            if (metrics.getVolume24hUsd() != null) {
                sb.append(String.format("📊 24h量: `$%s`\n", formatLarge(metrics.getVolume24hUsd())));
            }
            if (metrics.getHolderCount() != null) {
                sb.append(String.format("👥 持有者: `%d`\n", metrics.getHolderCount()));
            }
            if (metrics.getPriceChange1hPct() != null) {
                String pct = formatDecimal(metrics.getPriceChange1hPct(), 2);
                String arrow = metrics.getPriceChange1hPct().doubleValue() >= 0 ? "📈" : "📉";
                sb.append(String.format("%s 1h涨跌: `%s%%`\n", arrow, pct));
            }
        }

        // 合约地址
        sb.append("\n");
        sb.append(String.format("🔗 合约: `%s`\n", token.getContractAddress()));
        if (token.getPairAddress() != null && !token.getPairAddress().isBlank()) {
            sb.append(String.format("🏊 交易对: `%s`\n", token.getPairAddress()));
        }

        // 触发规则
        sb.append(String.format("\n✅ 触发规则: _%s_", escapeMarkdown(rule.getName())));

        return sb.toString();
    }

    private String gradeEmoji(int score) {
        if (score >= 80) return "S 🏆";
        if (score >= 65) return "A 🥇";
        if (score >= 50) return "B 🥈";
        if (score >= 35) return "C 🥉";
        return "D";
    }

    private void sendTelegramMessage(String text) {
        ChainAnalysisProperties.Telegram cfg = properties.getTelegram();
        String url = cfg.getApiUrl() + "/bot" + cfg.getBotToken() + "/sendMessage";

        String jsonBody = String.format(
                "{\"chat_id\":\"%s\",\"text\":\"%s\",\"parse_mode\":\"Markdown\"}",
                cfg.getChatId(), escapeJson(text));

        Request request = new Request.Builder()
                .url(url)
                .post(RequestBody.create(jsonBody, JSON_MEDIA))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String body = response.body() != null ? response.body().string() : "";
                log.warn("[TelegramChain] sendMessage failed, code={}, body={}", response.code(), body);
            } else {
                log.debug("[TelegramChain] Alert sent successfully");
            }
        } catch (Exception e) {
            log.warn("[TelegramChain] Failed to send alert: {}", e.getMessage());
        }
    }

    private String formatDecimal(BigDecimal val, int scale) {
        return val.setScale(scale, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    private String formatLarge(BigDecimal val) {
        double d = val.doubleValue();
        if (d >= 1_000_000) return String.format("%.2fM", d / 1_000_000);
        if (d >= 1_000) return String.format("%.1fK", d / 1_000);
        return String.format("%.2f", d);
    }

    private String escapeMarkdown(String text) {
        if (text == null) return "";
        return text.replace("_", "\\_").replace("*", "\\*").replace("`", "\\`").replace("[", "\\[");
    }

    private String escapeJson(String text) {
        return text.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "");
    }
}

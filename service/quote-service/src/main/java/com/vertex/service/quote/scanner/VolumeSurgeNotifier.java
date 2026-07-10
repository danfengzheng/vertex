package com.vertex.service.quote.scanner;

import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 成交量暴增告警的 Telegram 推送。
 * <p>
 * 与 yaml 级 volume-surge.enabled 联动注册；实际 Telegram 开/关走 DB 里的
 * {@code telegramEnabled} 字段，由 scanner 在调用前判断，不在这里做条件注册，
 * 便于用户在 UI 上热切换。
 * </p>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "vertex.quote.volume-surge", name = "enabled", havingValue = "true")
public class VolumeSurgeNotifier {

    private static final MediaType JSON_MEDIA = MediaType.parse("application/json; charset=utf-8");
    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("UTC"));

    private final OkHttpClient httpClient;

    public VolumeSurgeNotifier(@Qualifier("quoteOkHttpClient") OkHttpClient httpClient) {
        this.httpClient = httpClient;
    }

    /**
     * 用调用方提供的 Telegram 凭据发送告警。
     * @param apiUrl   Telegram Bot API 根地址（默认 https://api.telegram.org）
     * @param botToken 从 DB 配置取的 bot token
     * @param chatId   从 DB 配置取的 chat id
     */
    public void sendAlert(VolumeSurgeAlert alert, String apiUrl, String botToken, String chatId) {
        if (botToken == null || botToken.isBlank() || chatId == null || chatId.isBlank()) {
            log.debug("[VolumeSurgeNotifier] bot-token / chat-id blank, skip");
            return;
        }
        String base = (apiUrl == null || apiUrl.isBlank()) ? "https://api.telegram.org" : apiUrl;
        String text = buildMessage(alert);
        sendTelegram(base, botToken, chatId, text);
    }

    private String buildMessage(VolumeSurgeAlert a) {
        StringBuilder sb = new StringBuilder(512);
        String arrow = "UP".equals(a.getDirection()) ? "📈" : "📉";
        String timing = a.isTriggeredBeforeClose() ? "⚡实时" : "🕐收盘";
        sb.append(arrow).append(" *成交量暴增* | ").append(a.getExchange().toUpperCase())
          .append(" | ").append(timing).append("\n\n");

        sb.append("📌 *").append(escape(a.getSymbol())).append("*\n");
        sb.append(String.format("🚀 暴增倍数: `%.1fx`\n", a.getSurgeRatio()));
        // 未收盘时提示"这是 elapsedMinutes 分钟内的累计量"
        if (a.isTriggeredBeforeClose()) {
            sb.append(String.format("📊 %d 分钟累计: `$%s`  ← baseline 全小时中位 `$%s`\n",
                    a.getElapsedMinutes(),
                    fmtLarge(a.getCurrent1hQuoteUsdt()), fmtLarge(a.getBaselineMedianUsdt())));
        } else {
            sb.append(String.format("📊 1H 成交额: `$%s`  ← baseline 中位 `$%s`\n",
                    fmtLarge(a.getCurrent1hQuoteUsdt()), fmtLarge(a.getBaselineMedianUsdt())));
        }
        sb.append(String.format("💰 1H 价格: `%s%%` (`%s` → `%s`)\n",
                fmtPct(a.getPriceChange1hPct()),
                fmtPrice(a.getOpenPrice()), fmtPrice(a.getClosePrice())));
        sb.append(String.format("📅 24h 涨跌: `%s%%`   24h 量: `$%s`\n",
                fmtPct(a.getPriceChange24hPct()), fmtLarge(a.getVol24hUsdt())));
        sb.append(String.format("⏱  触发 K 线: `%s UTC`",
                TIME_FMT.format(Instant.ofEpochMilli(a.getTriggerBarOpenTime()))));
        if (a.isTriggeredBeforeClose()) {
            sb.append(String.format(" (进行中，已 %d 分钟)", a.getElapsedMinutes()));
        }
        sb.append('\n');

        if (a.getBaselineMedianUsdt() < 100_000d) {
            sb.append("\n⚠️ *小市值币警报，注意庄拉 / pump-and-dump 风险*");
        }
        return sb.toString();
    }

    private void sendTelegram(String apiUrl, String botToken, String chatId, String text) {
        String url = apiUrl + "/bot" + botToken + "/sendMessage";
        String jsonBody = String.format(
                "{\"chat_id\":\"%s\",\"text\":\"%s\",\"parse_mode\":\"Markdown\"}",
                chatId, escapeJson(text));

        Request req = new Request.Builder()
                .url(url)
                .post(RequestBody.create(jsonBody, JSON_MEDIA))
                .build();

        try (Response resp = httpClient.newCall(req).execute()) {
            if (!resp.isSuccessful()) {
                String body = resp.body() != null ? resp.body().string() : "";
                log.warn("[VolumeSurgeNotifier] Telegram send failed: code={}, body={}",
                        resp.code(), body);
            }
        } catch (Exception e) {
            log.warn("[VolumeSurgeNotifier] Telegram send exception: {}", e.getMessage());
        }
    }

    private static String fmtLarge(double v) {
        double abs = Math.abs(v);
        if (abs >= 1_000_000) return String.format("%.2fM", v / 1_000_000);
        if (abs >= 1_000) return String.format("%.1fK", v / 1_000);
        return String.format("%.0f", v);
    }

    private static String fmtPct(double v) {
        return (v >= 0 ? "+" : "") + String.format("%.2f", v);
    }

    private static String fmtPrice(double v) {
        if (v >= 1) return String.format("%.4f", v);
        if (v >= 0.01) return String.format("%.6f", v);
        return String.format("%.8f", v);
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("_", "\\_").replace("*", "\\*").replace("`", "\\`").replace("[", "\\[");
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "");
    }
}

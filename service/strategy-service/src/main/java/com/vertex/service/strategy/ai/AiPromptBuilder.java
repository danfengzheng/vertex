package com.vertex.service.strategy.ai;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.vertex.model.entity.quote.KLine;
import com.vertex.model.entity.strategy.Signal;
import com.vertex.model.entity.strategy.Strategy;
import com.vertex.model.vo.strategy.BacktestResultVO;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 构造 Gemini Prompt 与对应的 responseSchema。
 * <p>
 * 与 GeminiClient 解耦：本类只关心"喂给 AI 什么、要 AI 返回什么结构"，
 * 不关心 HTTP 调用细节。
 * </p>
 */
public final class AiPromptBuilder {

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("UTC"));

    private AiPromptBuilder() {}

    /**
     * 把 BCP-47 风格的 language 标签转成喂给模型的人类可读语言名。
     * 模型对「Chinese (Simplified)」/「English」之类字面识别效果好得多。
     */
    private static String languageDisplay(String lang) {
        if (lang == null) return "Chinese (Simplified)";
        String s = lang.trim().toLowerCase();
        if (s.isEmpty() || s.startsWith("zh")) return "Chinese (Simplified)";
        if (s.startsWith("en")) return "English";
        if (s.startsWith("ja")) return "Japanese";
        if (s.startsWith("ko")) return "Korean";
        // 透传给模型：让它自己理解 BCP-47 标签
        return lang;
    }

    /**
     * 统一的「自由文本用什么语言、枚举字段保持英文」指令片段。
     */
    private static void appendLanguageDirective(StringBuilder sb, String language) {
        String lang = languageDisplay(language);
        sb.append("\n=== Output language ===\n")
          .append("Respond in ").append(lang).append(" for ALL free-text fields ")
          .append("(summary, keyFactors, risks, entryFactors, exitFactors, improvements). ")
          .append("Keep enum values (verdict, alignment, marketRegime, suggestedAction) ")
          .append("as their original English keys exactly as defined in the schema. ")
          .append("Do NOT translate enum keys.\n");
    }

    // ─── Signal 分析 ──────────────────────────────────────────────

    /**
     * 构造单条信号分析的 prompt（默认中文输出，保留旧调用方兼容）。
     */
    public static String buildSignalPrompt(Strategy strategy, Signal signal, List<KLine> recentKlines) {
        return buildSignalPrompt(strategy, signal, recentKlines, "zh-CN");
    }

    /**
     * 构造单条信号分析的 prompt，指定输出语言。
     */
    public static String buildSignalPrompt(Strategy strategy, Signal signal, List<KLine> recentKlines,
                                           String language) {
        StringBuilder sb = new StringBuilder(2048);
        sb.append("You are a professional quantitative trading analyst. ")
          .append("Analyze the following trading signal STRICTLY based on the provided data. ")
          .append("Do not use any external knowledge about the market beyond what is given. ")
          .append("Your output is for reference only and will NOT automatically execute trades.\n\n");

        sb.append("=== Strategy ===\n");
        sb.append("Exchange: ").append(strategy.getExchange()).append('\n');
        sb.append("Symbol: ").append(strategy.getSymbol()).append('\n');
        sb.append("Interval: ").append(strategy.getInterval()).append('\n');
        sb.append("Stop-Loss %: ").append(strategy.getStopLossPct()).append('\n');
        sb.append("Take-Profit %: ").append(strategy.getTakeProfitPct()).append('\n');
        if (strategy.getTakeProfitPct1() != null) {
            sb.append("Staged TP: ")
                    .append(strategy.getTakeProfitPct1()).append("% (")
                    .append(strategy.getTakeProfitSize1()).append("%) / ")
                    .append(strategy.getTakeProfitPct2()).append("% (")
                    .append(strategy.getTakeProfitSize2()).append("%) / ")
                    .append(strategy.getTakeProfitPct3()).append("% (")
                    .append(strategy.getTakeProfitSize3()).append("%)\n");
        }

        sb.append("\n=== Signal ===\n");
        sb.append("Type: ").append(signal.getSignalType()).append('\n');
        sb.append("Strength (TA-vote): ").append(signal.getSignalStrength()).append("/100\n");
        sb.append("Price: ").append(signal.getPrice()).append('\n');
        sb.append("Time: ").append(formatTime(signal.getSignalTime())).append(" UTC\n");
        if (signal.getIndicators() != null) {
            sb.append("Indicator values:\n");
            try {
                JSONObject indicators = JSON.parseObject(signal.getIndicators());
                for (Map.Entry<String, Object> e : indicators.entrySet()) {
                    sb.append("  - ").append(e.getKey()).append(": ").append(e.getValue()).append('\n');
                }
            } catch (Exception ignored) {
                sb.append("  ").append(signal.getIndicators()).append('\n');
            }
        }

        sb.append("\n=== Recent K-lines (most recent last, ")
          .append(recentKlines == null ? 0 : recentKlines.size())
          .append(" bars; window sized to cover the longest indicator's lookback) ===\n");
        // 显示全部传入的 K 线：调用方已经在 query 时按指标 requiredDataPoints cap 过数量，
        // 这里再 cap 等于双重截断；直接用 Integer.MAX_VALUE 表示「展示全部」。
        appendKlines(sb, recentKlines, Integer.MAX_VALUE);

        sb.append("\n=== Task ===\n");
        sb.append("Return a JSON conforming exactly to the response schema. ")
          .append("Be specific about which indicator value drives your judgment. ")
          .append("Keep keyFactors/risks to 2-4 items each, summary in one sentence.\n");
        appendLanguageDirective(sb, language);
        return sb.toString();
    }

    /**
     * AiSignalAnalysis 对应的 Gemini responseSchema。
     */
    public static JSONObject buildSignalSchema() {
        return schemaObject(Map.of(
                "confidence",  schemaNumber("0-1, AI confidence on this signal's direction"),
                "alignment",   schemaEnum(List.of("ALIGNED", "NEUTRAL", "DIVERGED")),
                "marketRegime", schemaEnum(List.of("TRENDING", "RANGING", "VOLATILE", "CALM")),
                "keyFactors",  schemaStringArray(),
                "risks",       schemaStringArray(),
                "suggestedAction", schemaEnum(List.of(
                        "ENTER_FULL", "ENTER_HALF", "ENTER_WITH_TIGHT_STOP", "OBSERVE", "SKIP")),
                "summary",     schemaString("One-sentence summary")
        ), List.of("confidence", "alignment", "marketRegime", "summary"));
    }

    // ─── Trade 分析（回测专用） ──────────────────────────────────

    /** 兼容旧签名：默认中文输出。*/
    public static String buildTradePrompt(Strategy strategy,
                                          BacktestResultVO.TradeRecord trade,
                                          int tradeIndex,
                                          int totalTrades,
                                          List<KLine> contextKlines) {
        return buildTradePrompt(strategy, trade, tradeIndex, totalTrades, contextKlines, "zh-CN");
    }

    public static String buildTradePrompt(Strategy strategy,
                                          BacktestResultVO.TradeRecord trade,
                                          int tradeIndex,
                                          int totalTrades,
                                          List<KLine> contextKlines,
                                          String language) {
        StringBuilder sb = new StringBuilder(2048);
        sb.append("You are a quantitative backtest analyst. ")
          .append("Review this single trade from a strategy backtest and explain its outcome. ")
          .append("Do not use external market knowledge beyond the data provided.\n\n");

        sb.append("=== Strategy ===\n");
        sb.append("Symbol: ").append(strategy.getExchange()).append(' ').append(strategy.getSymbol()).append('\n');
        sb.append("Interval: ").append(strategy.getInterval()).append('\n');

        sb.append("\n=== Trade ").append(tradeIndex + 1).append('/').append(totalTrades).append(" ===\n");
        sb.append("Type: ").append(trade.getType()).append('\n');
        sb.append("Entry: ").append(formatTime(trade.getEntryTime()))
                .append(" @ ").append(trade.getEntryPrice()).append('\n');
        sb.append("Exit:  ").append(formatTime(trade.getExitTime()))
                .append(" @ ").append(trade.getExitPrice()).append('\n');
        sb.append("Quantity: ").append(trade.getQuantity()).append('\n');
        sb.append("Profit: ").append(trade.getProfit()).append(" (").append(trade.getProfitPercent()).append("%)\n");
        sb.append("Exit reason: ").append(trade.getExitReason()).append('\n');

        sb.append("\n=== Context K-lines (around entry, oldest first, ")
          .append(contextKlines == null ? 0 : contextKlines.size())
          .append(" bars; window sized to cover the longest indicator's lookback) ===\n");
        appendKlines(sb, contextKlines, Integer.MAX_VALUE);

        sb.append("\n=== Task ===\n");
        sb.append("Score this trade quality 0-1, classify the verdict, ")
          .append("list 2-3 entry factors and 1-3 exit factors, ")
          .append("optionally 1-2 improvement suggestions. Output JSON per schema.\n");
        appendLanguageDirective(sb, language);
        return sb.toString();
    }

    public static JSONObject buildTradeSchema() {
        return schemaObject(Map.of(
                "quality", schemaNumber("0-1 trade quality score"),
                "verdict", schemaEnum(List.of(
                        "GOOD_ENTRY", "LATE_ENTRY", "FALSE_SIGNAL",
                        "GOOD_EXIT", "EARLY_EXIT", "BAD_STOP_LOSS", "LUCKY_PROFIT")),
                "entryFactors", schemaStringArray(),
                "exitFactors", schemaStringArray(),
                "improvements", schemaStringArray(),
                "summary", schemaString("One-sentence summary")
        ), List.of("quality", "verdict", "summary"));
    }

    // ─── Schema builders ─────────────────────────────────────────

    /** Gemini OBJECT 类型 schema，properties 用 LinkedHashMap 保证字段顺序 */
    private static JSONObject schemaObject(Map<String, JSONObject> props, List<String> required) {
        JSONObject obj = new JSONObject();
        obj.put("type", "OBJECT");
        JSONObject propsObj = new JSONObject();
        for (Map.Entry<String, JSONObject> e : props.entrySet()) {
            propsObj.put(e.getKey(), e.getValue());
        }
        obj.put("properties", propsObj);
        JSONArray reqArr = new JSONArray();
        reqArr.addAll(required);
        obj.put("required", reqArr);
        return obj;
    }

    private static JSONObject schemaString(String desc) {
        JSONObject o = new JSONObject();
        o.put("type", "STRING");
        if (desc != null) o.put("description", desc);
        return o;
    }

    private static JSONObject schemaNumber(String desc) {
        JSONObject o = new JSONObject();
        o.put("type", "NUMBER");
        if (desc != null) o.put("description", desc);
        return o;
    }

    private static JSONObject schemaEnum(List<String> values) {
        JSONObject o = new JSONObject();
        o.put("type", "STRING");
        JSONArray arr = new JSONArray();
        arr.addAll(values);
        o.put("enum", arr);
        return o;
    }

    private static JSONObject schemaStringArray() {
        JSONObject o = new JSONObject();
        o.put("type", "ARRAY");
        o.put("items", schemaString(null));
        return o;
    }

    // ─── Helpers ─────────────────────────────────────────────────

    private static void appendKlines(StringBuilder sb, List<KLine> klines, int max) {
        if (klines == null || klines.isEmpty()) {
            sb.append("(no data)\n");
            return;
        }
        int from = Math.max(0, klines.size() - max);
        for (int i = from; i < klines.size(); i++) {
            KLine k = klines.get(i);
            sb.append(formatTime(k.getOpenTime()))
                    .append("  O=").append(fmt(k.getOpen()))
                    .append(" H=").append(fmt(k.getHigh()))
                    .append(" L=").append(fmt(k.getLow()))
                    .append(" C=").append(fmt(k.getClose()))
                    .append(" V=").append(fmt(k.getVolume()))
                    .append('\n');
        }
    }

    private static String fmt(BigDecimal v) {
        return v == null ? "-" : v.stripTrailingZeros().toPlainString();
    }

    private static String formatTime(Long ms) {
        return ms == null ? "n/a" : TIME_FMT.format(Instant.ofEpochMilli(ms));
    }
}

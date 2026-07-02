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
     * 双 prompt 输出（system + user），供 provider 走原生 system message 通道。
     * 老调用方仍可用 {@code buildSignalPrompt(...)} / {@code buildTradePrompt(...)}
     * 拿到拼接后的单 prompt 字符串。
     */
    public static final class PromptParts {
        public final String systemPrompt;
        public final String userPrompt;
        public PromptParts(String systemPrompt, String userPrompt) {
            this.systemPrompt = systemPrompt;
            this.userPrompt = userPrompt;
        }
        /** 单 prompt 兼容形式：system + \n\n + user */
        public String combined() {
            return systemPrompt + "\n\n" + userPrompt;
        }
    }

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
     * 构造 system prompt：语言硬约束 + 角色声明。这段作为高优先级指令，
     * 通过 provider 的 system message 通道下发（DeepSeek 用 role:system，
     * Gemini 用 systemInstruction）—— 权重比 user 内容高很多，避免被
     * 后面的英文业务上下文带偏语言。
     * <p>
     * 用「CRITICAL / MUST / FORBIDDEN + 正反例」的组合，实测能显著抬高
     * LLM（特别是 deepseek-chat）对语言指令的遵从率。
     * </p>
     */
    private static String buildSystemPrompt(String language, boolean isTradeAnalysis) {
        String lang = languageDisplay(language);
        boolean isZh = lang.startsWith("Chinese");
        StringBuilder sb = new StringBuilder(1024);

        // ── 语言硬约束（最重要，放最前面）────────────────────────
        sb.append("CRITICAL LANGUAGE RULE (must follow, non-negotiable):\n")
          .append("All free-text fields (summary, keyFactors, risks, entryFactors, exitFactors, improvements)\n")
          .append("MUST be written in ").append(lang).append(". English or mixed-language is FORBIDDEN\n")
          .append("in these fields even if surrounding context is in English.\n")
          .append("Enum fields (verdict, alignment, marketRegime, suggestedAction) MUST keep their ORIGINAL\n")
          .append("English keys exactly as defined in the schema — do NOT translate enum keys.\n\n");

        // ── 正反例（对中文尤其有效）───────────────────────────
        if (isZh) {
            sb.append("CORRECT format example:\n")
              .append("  summary: \"强势上升趋势中 ADX 和 DI 对齐，但成交量下降建议观望。\"\n")
              .append("  keyFactors: [\"ADX 48.84 显示强趋势\", \"PlusDI 29.87 > MinusDI 12.26 支持看涨\"]\n")
              .append("  risks: [\"成交量比 0.82 走弱，趋势可能失续\"]\n")
              .append("  alignment: \"ALIGNED\"          ← 保持英文枚举\n")
              .append("  suggestedAction: \"OBSERVE\"    ← 保持英文枚举\n\n")
              .append("WRONG (do NOT do this):\n")
              .append("  summary: \"Strong uptrend with ADX and DI alignment...\"  ← 全英文，违规\n")
              .append("  keyFactors: [\"ADX at 48.84 indicates strong trend\"]     ← 全英文，违规\n")
              .append("  alignment: \"一致\"                                        ← 枚举被翻译，违规\n\n");
        } else {
            sb.append("CORRECT format example:\n")
              .append("  summary: \"Strong uptrend with ADX/DI alignment; declining volume suggests caution.\"\n")
              .append("  keyFactors: [\"ADX 48.84 shows strong trend\", \"PlusDI > MinusDI supports bullish bias\"]\n")
              .append("  alignment: \"ALIGNED\"          ← keep enum keys unchanged\n\n");
        }

        // ── 角色（原来在 user prompt 里的 system role 现在提上来）─
        if (isTradeAnalysis) {
            sb.append("You are a quantitative backtest analyst. Review a single trade from a strategy ")
              .append("backtest and explain its outcome strictly from the data provided. ")
              .append("Do not use external market knowledge.\n");
        } else {
            sb.append("You are a professional quantitative trading analyst. Analyze the trading signal ")
              .append("STRICTLY based on the provided data. Do not use external knowledge beyond what is given. ")
              .append("Your output is for reference only and will NOT automatically execute trades.\n");
        }
        return sb.toString();
    }

    // ─── Signal 分析 ──────────────────────────────────────────────

    /**
     * 构造单条信号分析的 prompt（默认中文输出，保留旧调用方兼容）。
     */
    public static String buildSignalPrompt(Strategy strategy, Signal signal, List<KLine> recentKlines) {
        return buildSignalPromptParts(strategy, signal, recentKlines, "zh-CN").combined();
    }

    /**
     * 构造单条信号分析的 prompt，指定输出语言。
     */
    public static String buildSignalPrompt(Strategy strategy, Signal signal, List<KLine> recentKlines,
                                           String language) {
        return buildSignalPromptParts(strategy, signal, recentKlines, language).combined();
    }

    /**
     * 构造 signal 分析的双 prompt（system + user）。
     * 语言约束和角色声明放到 systemPrompt，业务上下文放到 userPrompt。
     */
    public static PromptParts buildSignalPromptParts(Strategy strategy, Signal signal,
                                                     List<KLine> recentKlines, String language) {
        String systemPrompt = buildSystemPrompt(language, false);
        StringBuilder sb = new StringBuilder(2048);
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
          .append("Keep keyFactors/risks to 2-4 items each, summary in one sentence.\n")
          .append("REMINDER: free-text fields MUST use the language set in the system message; enum keys stay English.\n");
        return new PromptParts(systemPrompt, sb.toString());
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
        return buildTradePromptParts(strategy, trade, tradeIndex, totalTrades, contextKlines, "zh-CN").combined();
    }

    public static String buildTradePrompt(Strategy strategy,
                                          BacktestResultVO.TradeRecord trade,
                                          int tradeIndex,
                                          int totalTrades,
                                          List<KLine> contextKlines,
                                          String language) {
        return buildTradePromptParts(strategy, trade, tradeIndex, totalTrades, contextKlines, language).combined();
    }

    /** 构造 trade 分析的双 prompt（system + user）。*/
    public static PromptParts buildTradePromptParts(Strategy strategy,
                                                    BacktestResultVO.TradeRecord trade,
                                                    int tradeIndex,
                                                    int totalTrades,
                                                    List<KLine> contextKlines,
                                                    String language) {
        String systemPrompt = buildSystemPrompt(language, true);
        StringBuilder sb = new StringBuilder(2048);
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
          .append("optionally 1-2 improvement suggestions. Output JSON per schema.\n")
          .append("REMINDER: free-text fields MUST use the language set in the system message; enum keys stay English.\n");
        return new PromptParts(systemPrompt, sb.toString());
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

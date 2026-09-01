package com.vertex.service.strategy.template;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.vertex.model.entity.quote.KLineInterval;
import com.vertex.model.entity.strategy.Strategy;
import com.vertex.model.entity.strategy.StrategyTemplate;
import com.vertex.model.vo.strategy.TemplateExposedParam;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模板 → Strategy 构造器。
 * <p>
 * 流程：
 * <ol>
 *   <li>反序列化 template_json 为 Map</li>
 *   <li>反序列化 exposed_params_json 为 List&lt;TemplateExposedParam&gt;</li>
 *   <li>把用户填的 userParams 按 templatePath 注入到 template 的对应位置</li>
 *   <li>把 template 里的顶层字段拷贝到 Strategy 实体（stopLossPct/positionRatio/indicators/…）</li>
 *   <li>特殊路径 {@code $strategy.<field>} 直接设置到 Strategy 顶层字段</li>
 * </ol>
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StrategyTemplateParser {

    private final TemplateValidator validator;

    /**
     * 主入口：模板 + 用户参数 → 完整 Strategy 实体（未落库）。
     *
     * @param strategyNameOverride 可选，覆盖策略名；null 则用 template.name + 时间戳
     */
    public Strategy build(StrategyTemplate tpl, Map<String, Object> userParams, String strategyNameOverride) {
        if (userParams == null) userParams = new LinkedHashMap<>();

        // 解析模板
        Object template = JSON.parse(tpl.getTemplateJson());
        List<TemplateExposedParam> exposed = JSON.parseArray(tpl.getExposedParamsJson(), TemplateExposedParam.class);

        // 校验（严格）
        TemplateValidator.ValidationResult vr = validator.validate(template, exposed);
        if (!vr.valid()) {
            throw new IllegalStateException("模板结构非法：" + String.join("; ", vr.errors()));
        }

        // 拷贝一份，避免修改 template_json 原始 Map（如果 JSON.parse 复用了 cache）
        Map<String, Object> templateMap = JSON.parseObject(JSON.toJSONString(template));

        Strategy strategy = new Strategy();
        String defaultName = tpl.getName() + " · "
                + LocalDateTime.now().format(DateTimeFormatter.ofPattern("MMdd-HHmm"));
        strategy.setName(strategyNameOverride != null && !strategyNameOverride.isBlank()
                ? strategyNameOverride : defaultName);
        strategy.setDescription("Created from template: " + tpl.getCode() + " v" + tpl.getVersion());
        strategy.setEnabled(1);
        strategy.setSourceTemplateId(tpl.getId());
        strategy.setSourceTemplateVersion(tpl.getVersion());
        strategy.setTemplateUserParamsJson(JSON.toJSONString(userParams));

        // 注入用户参数
        for (TemplateExposedParam p : exposed) {
            Object val = userParams.getOrDefault(p.getUserKey(), p.getDefault());
            if (val == null && Boolean.TRUE.equals(p.getRequired())) {
                throw new IllegalArgumentException("必填参数缺失：" + p.getUserKey());
            }
            if (val == null) continue;

            // number 类型校验范围
            if ("number".equalsIgnoreCase(p.getType()) && val instanceof Number) {
                double d = ((Number) val).doubleValue();
                if (p.getMin() != null && d < p.getMin().doubleValue()) {
                    throw new IllegalArgumentException(p.getUserKey() + " 小于最小值 " + p.getMin());
                }
                if (p.getMax() != null && d > p.getMax().doubleValue()) {
                    throw new IllegalArgumentException(p.getUserKey() + " 大于最大值 " + p.getMax());
                }
            }

            String path = p.getTemplatePath();
            if (TemplateValidator.isStrategyRootPath(path)) {
                // $strategy.xxx → 直接设到 Strategy 顶层字段
                applyStrategyRootField(strategy, path.substring("$strategy.".length()), val);
            } else {
                JsonPathHelper.set(templateMap, path, val);
            }
        }

        // template → Strategy 字段拷贝（indicators / stopLossPct / positionRatio / ...）
        applyTemplateToStrategy(templateMap, strategy);

        // 兜底
        if (strategy.getExchange() == null) strategy.setExchange("binance");

        return strategy;
    }

    // ─── template Map → Strategy 字段 ────────────────────

    @SuppressWarnings("unchecked")
    private void applyTemplateToStrategy(Map<String, Object> tpl, Strategy s) {
        // indicators → JSON 字符串写入 indicatorConfigs
        Object indicators = tpl.get("indicators");
        if (indicators != null) {
            s.setIndicatorConfigs(JSON.toJSONString(indicators, JSONWriter.Feature.WriteMapNullValue));
        }
        Object exitIndicators = tpl.get("exitIndicatorConfigs");
        if (exitIndicators != null) {
            s.setExitIndicatorConfigs(JSON.toJSONString(exitIndicators, JSONWriter.Feature.WriteMapNullValue));
        }

        setBigDecimalIfPresent(tpl, "positionRatio",        s::setPositionRatio);
        setBigDecimalIfPresent(tpl, "initialCapital",       s::setInitialCapital);
        setBigDecimalIfPresent(tpl, "stopLossPct",          s::setStopLossPct);
        setBigDecimalIfPresent(tpl, "takeProfitPct",        s::setTakeProfitPct);
        setBigDecimalIfPresent(tpl, "feeRate",              s::setFeeRate);
        setBigDecimalIfPresent(tpl, "atrStopMultiplier",    s::setAtrStopMultiplier);
        setBigDecimalIfPresent(tpl, "atrTakeProfitMultiplier", s::setAtrTakeProfitMultiplier);
        setBigDecimalIfPresent(tpl, "takeProfitPct1",       s::setTakeProfitPct1);
        setBigDecimalIfPresent(tpl, "takeProfitSize1",      s::setTakeProfitSize1);
        setBigDecimalIfPresent(tpl, "takeProfitPct2",       s::setTakeProfitPct2);
        setBigDecimalIfPresent(tpl, "takeProfitSize2",      s::setTakeProfitSize2);
        setBigDecimalIfPresent(tpl, "takeProfitPct3",       s::setTakeProfitPct3);
        setBigDecimalIfPresent(tpl, "takeProfitSize3",      s::setTakeProfitSize3);
        setBigDecimalIfPresent(tpl, "initialStopMultiplier",         s::setInitialStopMultiplier);
        setBigDecimalIfPresent(tpl, "breakevenActivationMultiplier", s::setBreakevenActivationMultiplier);
        setBigDecimalIfPresent(tpl, "trailingActivationMultiplier",  s::setTrailingActivationMultiplier);
        setBigDecimalIfPresent(tpl, "trailingDistanceMultiplier",    s::setTrailingDistanceMultiplier);
        setBigDecimalIfPresent(tpl, "trailingDropPct",      s::setTrailingDropPct);
        setBigDecimalIfPresent(tpl, "superTrendSlOffsetPct",s::setSuperTrendSlOffsetPct);
        setBigDecimalIfPresent(tpl, "exitOnOppositeVoteRatio", s::setExitOnOppositeVoteRatio);
        setBigDecimalIfPresent(tpl, "tradeQuantity",        s::setTradeQuantity);

        setIntIfPresent(tpl, "autoTrade",                   s::setAutoTrade);
        setIntIfPresent(tpl, "minSignalStrength",           s::setMinSignalStrength);
        setIntIfPresent(tpl, "leverage",                    s::setLeverage);
        setIntIfPresent(tpl, "maxHoldingBars",              s::setMaxHoldingBars);
        setIntIfPresent(tpl, "moveStopToBreakevenAfterStage", s::setMoveStopToBreakevenAfterStage);
        setIntIfPresent(tpl, "pauseOnStopLoss",             s::setPauseOnStopLoss);

        // enum-like 字段
        Object atrInterval = tpl.get("atrInterval");
        if (atrInterval != null) {
            s.setAtrInterval(parseInterval(atrInterval.toString()));
        }
    }

    /** 优先按 code（"5m"/"1h"）解析，失败再退回按枚举名（M5/H1）解析。 */
    private static KLineInterval parseInterval(String s) {
        try {
            return KLineInterval.fromCode(s);
        } catch (Exception ignored) {
            return KLineInterval.valueOf(s);
        }
    }

    /**
     * $strategy.xxx 特殊路径 —— 目前支持 symbol / exchange / interval /
     * name / description / autoTrade / positionRatio 等常用字段。
     */
    private void applyStrategyRootField(Strategy s, String field, Object val) {
        switch (field) {
            case "symbol"       -> s.setSymbol(String.valueOf(val));
            case "exchange"     -> s.setExchange(String.valueOf(val));
            case "interval"     -> s.setInterval(parseInterval(String.valueOf(val)));
            case "name"         -> s.setName(String.valueOf(val));
            case "description"  -> s.setDescription(String.valueOf(val));
            case "autoTrade"    -> s.setAutoTrade(toInt(val));
            default             -> log.warn("[TemplateParser] unknown $strategy.{} - ignored", field);
        }
    }

    // ─── 辅助 ─────────────────────────────────────────────────

    private static void setBigDecimalIfPresent(Map<String, Object> tpl, String key,
                                               java.util.function.Consumer<BigDecimal> setter) {
        Object v = tpl.get(key);
        if (v == null) return;
        setter.accept(toBig(v));
    }

    private static void setIntIfPresent(Map<String, Object> tpl, String key,
                                        java.util.function.Consumer<Integer> setter) {
        Object v = tpl.get(key);
        if (v == null) return;
        setter.accept(toInt(v));
    }

    private static BigDecimal toBig(Object v) {
        if (v instanceof BigDecimal b) return b;
        if (v instanceof Number n) return new BigDecimal(n.toString());
        if (v instanceof Boolean b) return b ? BigDecimal.ONE : BigDecimal.ZERO;
        return new BigDecimal(v.toString());
    }

    private static Integer toInt(Object v) {
        if (v instanceof Number n) return n.intValue();
        if (v instanceof Boolean b) return b ? 1 : 0;
        return Integer.parseInt(v.toString());
    }
}

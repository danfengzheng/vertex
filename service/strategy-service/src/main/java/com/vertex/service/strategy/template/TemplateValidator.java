package com.vertex.service.strategy.template;

import com.alibaba.fastjson2.JSON;
import com.vertex.model.entity.strategy.IndicatorType;
import com.vertex.model.vo.strategy.TemplateExposedParam;
import com.vertex.service.strategy.indicator.IndicatorRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 模板校验：schema 结构 + 引用完整性（指标类型是否存在）+ 暴露参数合法性。
 * <p>
 * 失败信息聚合返回，方便前端一次显示所有问题。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TemplateValidator {

    private final IndicatorRegistry indicatorRegistry;

    public ValidationResult validate(Object template, List<TemplateExposedParam> exposedParams) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        if (!(template instanceof Map)) {
            errors.add("template 必须是 JSON 对象");
            return new ValidationResult(false, errors, warnings);
        }
        Map<?, ?> tpl = (Map<?, ?>) template;

        // 1) 指标数组
        Object indicatorsObj = tpl.get("indicators");
        if (!(indicatorsObj instanceof List) || ((List<?>) indicatorsObj).isEmpty()) {
            errors.add("template.indicators 必须是非空数组");
        } else {
            List<?> indicators = (List<?>) indicatorsObj;
            for (int i = 0; i < indicators.size(); i++) {
                Object item = indicators.get(i);
                if (!(item instanceof Map)) {
                    errors.add("template.indicators[" + i + "] 必须是对象");
                    continue;
                }
                Map<?, ?> ind = (Map<?, ?>) item;
                Object typeObj = ind.get("indicatorType");
                if (typeObj == null) {
                    errors.add("template.indicators[" + i + "].indicatorType 必填");
                    continue;
                }
                IndicatorType type;
                try {
                    type = IndicatorType.fromCode(typeObj.toString());
                } catch (Exception ex) {
                    errors.add("template.indicators[" + i + "].indicatorType 未知：" + typeObj);
                    continue;
                }
                if (!indicatorRegistry.supports(type)) {
                    errors.add("template.indicators[" + i + "].indicatorType 未在 IndicatorRegistry 注册："
                            + type + "（需先实现对应 @Component 指标类）");
                }
            }
        }

        // 2) schemaVersion
        Object sv = tpl.get("schemaVersion");
        if (sv == null) {
            warnings.add("template.schemaVersion 建议显式指定");
        }

        // 3) exposedParams
        if (exposedParams == null || exposedParams.isEmpty()) {
            warnings.add("exposedParams 为空 —— 用户创建时无任何可调参数");
        } else {
            for (int i = 0; i < exposedParams.size(); i++) {
                TemplateExposedParam p = exposedParams.get(i);
                if (p.getUserKey() == null || p.getUserKey().isBlank()) {
                    errors.add("exposedParams[" + i + "].userKey 必填");
                }
                if (p.getType() == null || p.getType().isBlank()) {
                    errors.add("exposedParams[" + i + "].type 必填 (string/number/bool/enum)");
                }
                if (p.getTemplatePath() == null || p.getTemplatePath().isBlank()) {
                    errors.add("exposedParams[" + i + "].templatePath 必填");
                } else if (!isStrategyRootPath(p.getTemplatePath())
                        && !JsonPathHelper.exists(template, p.getTemplatePath())) {
                    // $strategy.* 走 Strategy 顶层字段（parser 特殊处理，不必在 template 里存在）
                    errors.add("exposedParams[" + i + "].templatePath 无法在 template 中定位："
                            + p.getTemplatePath());
                }
                if ("number".equalsIgnoreCase(p.getType())) {
                    if (p.getMin() != null && p.getMax() != null
                            && p.getMin().doubleValue() > p.getMax().doubleValue()) {
                        errors.add("exposedParams[" + i + "] min > max");
                    }
                }
                if ("enum".equalsIgnoreCase(p.getType())
                        && (p.getOptions() == null || p.getOptions().isEmpty())) {
                    errors.add("exposedParams[" + i + "] type=enum 但 options 为空");
                }
            }
        }

        return new ValidationResult(errors.isEmpty(), errors, warnings);
    }

    /** JSON 字符串入口，供 Controller 直接调 */
    public ValidationResult validateJson(String templateJson, String exposedParamsJson) {
        Object tpl = JSON.parse(templateJson);
        List<TemplateExposedParam> exposed = JSON.parseArray(exposedParamsJson, TemplateExposedParam.class);
        return validate(tpl, exposed);
    }

    public static boolean isStrategyRootPath(String path) {
        return path != null && path.startsWith("$strategy.");
    }

    // ─── VO ────────────────────────────────────────────────

    public record ValidationResult(boolean valid, List<String> errors, List<String> warnings) {
        public boolean hasWarnings() { return warnings != null && !warnings.isEmpty(); }
    }
}

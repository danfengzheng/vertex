package com.vertex.model.dto.strategy;

import lombok.Data;

import java.io.Serializable;
import java.util.Map;

/**
 * "从模板创建/预览/回测"共用请求 DTO。
 * <ul>
 *   <li>build   —— 落库为新 Strategy 并返回</li>
 *   <li>preview —— 只生成 Strategy 结构（不落库），供前端 JSON 预览</li>
 *   <li>quickBacktest —— 预览 + 临时落库跑一次回测 + 立即删掉</li>
 * </ul>
 */
@Data
public class TemplateBuildDTO implements Serializable {

    private Long templateId;
    /** 用户填的暴露参数值：userKey → value */
    private Map<String, Object> userParams;

    /** build 时可指定策略名，为空则用 template.name + 时间戳 */
    private String strategyName;

    // ─── quickBacktest 额外字段 ──────────────────────
    private Long backtestStartTime;
    private Long backtestEndTime;
    private java.math.BigDecimal backtestInitialCapital;
}

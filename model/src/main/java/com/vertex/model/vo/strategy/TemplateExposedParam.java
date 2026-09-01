package com.vertex.model.vo.strategy;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 模板暴露参数规格（JSON 反序列化目标）。
 * <p>
 * type 支持：string / number / bool / enum。
 * templatePath 用简易 JSONPath 定位到 template_json 里的字段：
 * <ul>
 *   <li>{@code "positionRatio"} —— 顶层字段</li>
 *   <li>{@code "stopLoss.params.buffer"} —— 嵌套对象</li>
 *   <li>{@code "indicators[2].weight"} —— 数组下标</li>
 *   <li>{@code "$strategy.symbol"} —— 特殊前缀，直接写到 Strategy 顶层字段（不进 template_json）</li>
 * </ul>
 * </p>
 */
@Data
public class TemplateExposedParam implements Serializable {

    /** 参数 key（前端表单字段名 + 后端 userParams map 的 key） */
    private String userKey;
    /** 中文 label */
    private String label;
    /** 英文 label（可选） */
    private String labelEn;
    /** 描述/提示，可选 */
    private String description;
    /** 类型：string / number / bool / enum */
    private String type;
    /** 是否必填 */
    private Boolean required;
    /** 默认值（类型由 type 决定） */
    private Object defaultValue;
    /** number 类型的下限 */
    private Number min;
    /** number 类型的上限 */
    private Number max;
    /** number 步长 */
    private Number step;
    /** enum 的可选值 */
    private List<Object> options;
    /** 定位路径 —— parser 用这个把用户填的值注入到 template_json / Strategy 上 */
    private String templatePath;

    /** Jackson/fastjson 的 default 关键字兼容：JSON 里字段名叫 "default"，Java 里用 defaultValue */
    public Object getDefault() { return defaultValue; }
    public void setDefault(Object v) { this.defaultValue = v; }
}

package com.vertex.model.vo.strategy;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 模板列表/详情 VO —— JSON 字段解析成 Java 对象暴露给前端。
 */
@Data
public class StrategyTemplateVO implements Serializable {

    private Long id;
    private String code;
    private String name;
    private String description;
    private String category;
    private Integer version;
    private String status;
    private String riskProfile;
    private List<String> marketFit;
    private List<String> intervalFit;

    /** 模板骨架（Object，前端只做展示/编辑器；结构由 Parser 校验） */
    private Object template;
    /** 暴露参数规格列表 */
    private List<TemplateExposedParam> exposedParams;

    private String minEngineVersion;
    private Integer schemaVersion;
    private Integer isBuiltin;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}

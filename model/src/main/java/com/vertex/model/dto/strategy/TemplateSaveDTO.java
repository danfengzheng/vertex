package com.vertex.model.dto.strategy;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 保存/创建模板 DTO —— 直接接受 JSON 对象结构，
 * 服务层序列化后落库。
 */
@Data
public class TemplateSaveDTO implements Serializable {

    /** null=新建，非 null=更新 */
    private Long id;

    /** 唯一 code，英文（新建时必填；更新时允许改） */
    private String code;
    private String name;
    private String description;
    private String category;
    private String status;
    private String riskProfile;
    private List<String> marketFit;
    private List<String> intervalFit;

    /** 模板骨架（Map/List 结构，前端 JSON 编辑） */
    private Object template;
    private List<Object> exposedParams;

    private String minEngineVersion;
    private Integer schemaVersion;
    /** 保存的同时是否发布（写快照）；发布时可带 changeNote */
    private Boolean publish;
    private String changeNote;
}

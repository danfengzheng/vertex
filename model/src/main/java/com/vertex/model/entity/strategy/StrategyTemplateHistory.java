package com.vertex.model.entity.strategy;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 模板发布历史快照，每次 publish 存一份，支持回滚 + 审计。
 */
@Data
@TableName("strategy_template_history")
public class StrategyTemplateHistory implements Serializable {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long templateId;
    private Integer version;
    private String templateJsonSnapshot;
    private String exposedParamsSnapshot;
    private String changeNote;
    private Long changedBy;
    private LocalDateTime changedAt;
}

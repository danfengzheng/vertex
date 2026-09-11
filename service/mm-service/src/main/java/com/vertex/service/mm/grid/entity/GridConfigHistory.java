package com.vertex.service.mm.grid.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 做市模块 - 参数变更审计。
 */
@Data
@TableName("grid_config_history")
public class GridConfigHistory implements Serializable {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long gridStrategyId;
    private String fieldChanged;
    private String oldValue;
    private String newValue;

    /** USER / ADAPTIVE / EMERGENCY —— 谁改的 */
    private String changeSource;

    private Long changedBy;
    private LocalDateTime changedAt;
}

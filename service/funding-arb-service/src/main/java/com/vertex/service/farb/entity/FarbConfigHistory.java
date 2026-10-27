package com.vertex.service.farb.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 策略参数变更审计。update() 时非 null 且值变化的字段自动记一行。
 */
@Data
@TableName("farb_config_history")
public class FarbConfigHistory {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long strategyId;
    private String fieldChanged;
    private String oldValue;
    private String newValue;
    /** USER / SYSTEM / RECOVERY */
    private String changeSource;
    private Long changedBy;
    private LocalDateTime changedAt;
}

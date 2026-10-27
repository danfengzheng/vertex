package com.vertex.service.farb.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 资金费套利事件流水。前端 UI 按 strategy / event_type / severity 过滤展示。
 */
@Data
@TableName("farb_event_log")
public class FarbEventLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long strategyId;
    private Long positionId;
    private String symbol;
    /** 完整列表见 SQL COMMENT */
    private String eventType;
    /** DEBUG / INFO / WARN / ERROR */
    private String severity;
    /** JSON payload */
    private String payload;
    private LocalDateTime occurredAt;
}

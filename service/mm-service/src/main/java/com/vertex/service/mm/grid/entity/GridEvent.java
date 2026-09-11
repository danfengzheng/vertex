package com.vertex.service.mm.grid.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 做市模块 - 事件流水（诊断 + 复盘）。
 */
@Data
@TableName("grid_event")
public class GridEvent implements Serializable {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long gridStrategyId;

    /** START / STOP / FILL / RESHUFFLE / STEP_ADJUST / TREND_ENTER / TREND_EXIT /
     *  LAYER_CHANGE / LADDER_PLACE / LADDER_FILL / DELTA_ALERT / MARGIN_ALERT /
     *  MAX_POS_REACHED / EMERGENCY_STOP / WS_DISCONNECT / WS_RECONNECT / GTX_REJECTED */
    private String eventType;

    /** INFO / WARN / ERROR */
    private String severity;

    /** JSON 载荷 */
    private String payloadJson;

    private LocalDateTime occurredAt;
}

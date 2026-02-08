package com.vertex.model.entity.strategy;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.vertex.common.core.base.BaseEntity;
import com.vertex.model.entity.quote.KLineInterval;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 策略信号实体
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("stg_signal")
public class Signal extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 策略ID */
    private Long strategyId;

    /** 策略名称（冗余） */
    private String strategyName;

    /** 交易对 */
    private String symbol;

    /** 交易所 */
    private String exchange;

    /** K线周期 */
    @TableField("`interval`")
    private KLineInterval interval;

    /** 信号类型 */
    private SignalType signalType;

    /** 信号强度 0-100 */
    private Integer signalStrength;

    /** 触发价格 */
    private BigDecimal price;

    /** 信号时间（K线开盘时间戳 ms） */
    private Long signalTime;

    /** 指标计算值 JSON (Map<String, Object>) */
    private String indicators;

    /** 信号描述 */
    private String description;
}

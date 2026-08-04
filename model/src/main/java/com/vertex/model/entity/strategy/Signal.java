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

    /**
     * 投票统计（transient / 不落库）：只在同一次 evaluate → 消费的进程内传递，
     * 供 StrategyEngineService 在 signal 为 NEUTRAL 时判断"反向投票占比"决定是否平仓。
     * 通过 @TableField(exist=false) 告诉 MyBatis Plus 不映射到 DB 列。
     */
    @TableField(exist = false)
    private transient VoteBreakdown voteBreakdown;

    /**
     * 单根 K 线的投票分布 —— 只算投票指标（不含 FILTER），只计数（不算权重）。
     */
    public record VoteBreakdown(int buyCount, int sellCount, int neutralCount) {
        public int total() { return buyCount + sellCount + neutralCount; }
    }
}

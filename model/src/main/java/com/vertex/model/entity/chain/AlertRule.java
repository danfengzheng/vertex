package com.vertex.model.entity.chain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.vertex.common.core.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 链上告警规则实体
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("chn_alert_rule")
public class AlertRule extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 规则名称 */
    private String name;

    /** 目标链：BNB / SOLANA / ALL */
    private String chain;

    /** 最低综合评分触发告警（0-100） */
    private Integer minScore;

    /** 最低市值 USD 门槛（null=不限） */
    private BigDecimal minMarketCapUsd;

    /** 最低流动性 USD 门槛（null=不限） */
    private BigDecimal minLiquidityUsd;

    /** 最低持有者数（null=不限） */
    private Integer minHolderCount;

    /** Top10 持仓最高集中度百分比（null=不限） */
    private BigDecimal maxTop10HolderPct;

    /** 通知渠道 JSON 数组，如 ["telegram"] */
    private String notifyChannels;

    /** 是否要求流动性锁定：0=不要求 1=要求 null=不限 */
    private Integer requireLiquidityLocked;

    /** 是否启用：0-禁用 1-启用 */
    private Integer enabled;

    /** 描述 */
    private String description;
}

package com.vertex.model.entity.strategy;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.vertex.common.core.base.BaseEntity;
import com.vertex.model.entity.quote.KLineInterval;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 策略配置实体
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("stg_strategy")
public class Strategy extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 策略名称 */
    private String name;

    /** 策略描述 */
    private String description;

    /** 交易所 */
    private String exchange;

    /** 交易对 */
    private String symbol;

    /** K线周期 */
    @TableField("`interval`")
    private KLineInterval interval;

    /** 指标配置 JSON (List<StrategyIndicatorConfig>) */
    private String indicatorConfigs;

    /** 是否启用 0-禁用 1-启用 */
    private Integer enabled;
}

package com.vertex.model.dto.strategy;

import com.vertex.common.core.page.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 策略分页查询参数
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class StrategyQueryDTO extends PageQuery {

    /** 策略名称（模糊搜索） */
    private String name;

    /** 交易所 */
    private String exchange;

    /** 交易对 */
    private String symbol;

    /** 启用状态 */
    private Integer enabled;
}

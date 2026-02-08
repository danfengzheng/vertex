package com.vertex.model.dto.strategy;

import com.vertex.common.core.page.PageQuery;
import com.vertex.model.entity.quote.KLineInterval;
import com.vertex.model.entity.strategy.SignalType;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 信号分页查询参数
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class SignalQueryDTO extends PageQuery {

    /** 策略ID */
    private Long strategyId;

    /** 交易所 */
    private String exchange;

    /** 交易对 */
    private String symbol;

    /** K线周期 */
    private KLineInterval interval;

    /** 信号类型 */
    private SignalType signalType;

    /** 开始时间 */
    private Long startTime;

    /** 结束时间 */
    private Long endTime;
}

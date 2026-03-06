package com.vertex.model.dto.trading;

import com.vertex.model.entity.trading.ExecutionMode;
import com.vertex.model.entity.trading.MarketType;
import com.vertex.model.entity.trading.PositionStatus;
import com.vertex.model.query.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 持仓分页查询参数
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class PositionQueryDTO extends PageQuery {

    private Long strategyId;
    private String exchange;
    private String symbol;
    private PositionStatus status;
    private ExecutionMode tradeMode;
    /** 市场类型过滤 SPOT/USDM/COINM */
    private MarketType marketType;
}

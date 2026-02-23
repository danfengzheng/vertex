package com.vertex.model.dto.trading;

import com.vertex.model.entity.trading.ExecutionMode;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;

/**
 * 已平仓盈亏分析查询参数
 */
@Data
public class PnlAnalysisQueryDTO {

    /** 交易模式：实盘/模拟 */
    private ExecutionMode tradeMode;

    /** 策略ID */
    private Long strategyId;

    /** 交易对 */
    private String symbol;

    /** 账户ID */
    private Long accountId;

    /** 平仓时间起（ISO 日期时间或时间戳毫秒） */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime closedAtStart;

    /** 平仓时间止 */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime closedAtEnd;
}

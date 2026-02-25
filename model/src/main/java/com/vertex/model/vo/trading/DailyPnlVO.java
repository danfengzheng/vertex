package com.vertex.model.vo.trading;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 每日盈亏数据点（用于走势图）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DailyPnlVO implements Serializable {

    /** 日期，格式 yyyy-MM-dd */
    private String date;

    /** 当日已实现盈亏 */
    private BigDecimal dailyPnl;

    /** 累计已实现盈亏（截至当日） */
    private BigDecimal cumulativePnl;
}

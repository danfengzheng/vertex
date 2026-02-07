package com.vertex.model.dto.quote;

import com.vertex.model.entity.quote.KLineInterval;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * K线查询参数
 */
@Data
public class KLineQueryDTO {

    /** 交易对，如 BTC-USDT */
    @NotBlank(message = "交易对不能为空")
    private String symbol;

    /** 交易所，如 binance */
    @NotBlank(message = "交易所不能为空")
    private String exchange;

    /** K线周期 */
    @NotNull(message = "K线周期不能为空")
    private KLineInterval interval;

    /** 查询起始时间（毫秒时间戳） */
    private Long startTime;

    /** 查询结束时间（毫秒时间戳） */
    private Long endTime;

    /** 最大返回条数，默认500 */
    @Max(value = 1000, message = "单次查询最多1000条")
    private Integer limit = 500;
}

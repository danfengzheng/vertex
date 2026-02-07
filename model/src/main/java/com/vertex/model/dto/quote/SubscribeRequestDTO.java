package com.vertex.model.dto.quote;

import com.vertex.model.entity.quote.KLineInterval;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * K线订阅/取消订阅请求参数
 */
@Data
public class SubscribeRequestDTO {

    /** 交易所，如 binance、okx */
    @NotBlank(message = "交易所不能为空")
    private String exchange;

    /** 交易对，统一格式如 BTC-USDT */
    @NotBlank(message = "交易对不能为空")
    private String symbol;

    /** K线周期 */
    @NotNull(message = "K线周期不能为空")
    private KLineInterval interval;
}

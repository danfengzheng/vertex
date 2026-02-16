package com.vertex.model.dto.trading;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 交易账户创建参数
 */
@Data
public class ExchangeAccountCreateDTO {

    @NotBlank(message = "账户名称不能为空")
    private String name;

    /** 交易所，默认 binance */
    private String exchange = "binance";

    @NotBlank(message = "API Key不能为空")
    private String apiKey;

    @NotBlank(message = "API Secret不能为空")
    private String apiSecret;
}

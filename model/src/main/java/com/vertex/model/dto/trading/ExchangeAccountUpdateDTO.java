package com.vertex.model.dto.trading;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 交易账户更新参数
 */
@Data
public class ExchangeAccountUpdateDTO {

    @NotNull(message = "账户ID不能为空")
    private Long id;

    private String name;

    /** API Key（可选，为空不更新） */
    private String apiKey;

    /** API Secret（可选，为空不更新） */
    private String apiSecret;
}

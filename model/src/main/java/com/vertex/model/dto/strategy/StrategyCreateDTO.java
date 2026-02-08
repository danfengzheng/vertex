package com.vertex.model.dto.strategy;

import com.vertex.model.entity.quote.KLineInterval;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * 策略创建参数
 */
@Data
public class StrategyCreateDTO {

    @NotBlank(message = "策略名称不能为空")
    private String name;

    private String description;

    @NotBlank(message = "交易所不能为空")
    private String exchange;

    @NotBlank(message = "交易对不能为空")
    private String symbol;

    @NotNull(message = "K线周期不能为空")
    private KLineInterval interval;

    @NotEmpty(message = "指标配置不能为空")
    private List<StrategyIndicatorConfig> indicatorConfigs;
}

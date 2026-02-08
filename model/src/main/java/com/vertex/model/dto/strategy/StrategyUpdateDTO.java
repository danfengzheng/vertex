package com.vertex.model.dto.strategy;

import com.vertex.model.entity.quote.KLineInterval;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * 策略更新参数
 */
@Data
public class StrategyUpdateDTO {

    @NotNull(message = "策略ID不能为空")
    private Long id;

    private String name;

    private String description;

    private String exchange;

    private String symbol;

    private KLineInterval interval;

    private List<StrategyIndicatorConfig> indicatorConfigs;
}

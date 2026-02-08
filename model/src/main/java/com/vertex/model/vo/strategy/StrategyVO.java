package com.vertex.model.vo.strategy;

import com.vertex.model.dto.strategy.StrategyIndicatorConfig;
import com.vertex.model.entity.quote.KLineInterval;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 策略 VO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StrategyVO implements Serializable {

    private Long id;
    private String name;
    private String description;
    private String exchange;
    private String symbol;
    private KLineInterval interval;
    private List<StrategyIndicatorConfig> indicatorConfigs;
    private Integer enabled;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}

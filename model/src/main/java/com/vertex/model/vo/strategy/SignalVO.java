package com.vertex.model.vo.strategy;

import com.vertex.model.entity.quote.KLineInterval;
import com.vertex.model.entity.strategy.SignalType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 信号 VO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SignalVO implements Serializable {

    private Long id;
    private Long strategyId;
    private String strategyName;
    private String symbol;
    private String exchange;
    private KLineInterval interval;
    private SignalType signalType;
    private Integer signalStrength;
    private BigDecimal price;
    private Long signalTime;
    private Map<String, Object> indicators;
    private String description;
    private LocalDateTime createTime;
}

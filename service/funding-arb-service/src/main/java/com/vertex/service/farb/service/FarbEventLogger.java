package com.vertex.service.farb.service;

import com.alibaba.fastjson2.JSON;
import com.vertex.service.farb.entity.FarbEventLog;
import com.vertex.service.farb.mapper.FarbEventLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 资金费套利 - 事件流水写入器。
 * <p>只做写；查询由 Controller 直接查表。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FarbEventLogger {

    private final FarbEventLogMapper mapper;

    public void info(long strategyId, String eventType, Map<String, Object> payload) {
        write(strategyId, null, null, eventType, "INFO", payload);
    }

    public void warn(long strategyId, String eventType, Map<String, Object> payload) {
        write(strategyId, null, null, eventType, "WARN", payload);
    }

    public void error(long strategyId, String eventType, Map<String, Object> payload) {
        write(strategyId, null, null, eventType, "ERROR", payload);
    }

    public void positionInfo(long strategyId, long positionId, String symbol,
                              String eventType, Map<String, Object> payload) {
        write(strategyId, positionId, symbol, eventType, "INFO", payload);
    }

    public void positionWarn(long strategyId, long positionId, String symbol,
                              String eventType, Map<String, Object> payload) {
        write(strategyId, positionId, symbol, eventType, "WARN", payload);
    }

    public void positionError(long strategyId, long positionId, String symbol,
                               String eventType, Map<String, Object> payload) {
        write(strategyId, positionId, symbol, eventType, "ERROR", payload);
    }

    private void write(long strategyId, Long positionId, String symbol,
                        String eventType, String severity, Map<String, Object> payload) {
        try {
            FarbEventLog e = new FarbEventLog();
            e.setStrategyId(strategyId);
            e.setPositionId(positionId);
            e.setSymbol(symbol);
            e.setEventType(eventType);
            e.setSeverity(severity);
            e.setPayload(payload == null ? null : JSON.toJSONString(payload));
            e.setOccurredAt(LocalDateTime.now());
            mapper.insert(e);
        } catch (Exception ex) {
            log.warn("[FarbEventLogger] persist failed strategy={} type={}: {}",
                    strategyId, eventType, ex.getMessage());
        }
    }
}

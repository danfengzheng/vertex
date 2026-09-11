package com.vertex.service.mm.grid.service;

import com.alibaba.fastjson2.JSON;
import com.vertex.service.mm.grid.entity.GridEvent;
import com.vertex.service.mm.grid.mapper.GridEventMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * grid_event 表统一写入入口。异常吃掉不影响主流程。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GridEventLogger {

    private final GridEventMapper mapper;

    public void info(long strategyId, String eventType, Map<String, Object> payload) {
        write(strategyId, eventType, "INFO", payload);
    }

    public void warn(long strategyId, String eventType, Map<String, Object> payload) {
        write(strategyId, eventType, "WARN", payload);
    }

    public void error(long strategyId, String eventType, Map<String, Object> payload) {
        write(strategyId, eventType, "ERROR", payload);
    }

    private void write(long strategyId, String eventType, String severity, Map<String, Object> payload) {
        try {
            GridEvent e = new GridEvent();
            e.setGridStrategyId(strategyId);
            e.setEventType(eventType);
            e.setSeverity(severity);
            e.setPayloadJson(payload == null ? "{}" : JSON.toJSONString(payload));
            e.setOccurredAt(LocalDateTime.now());
            mapper.insert(e);
        } catch (Exception ex) {
            log.warn("[GridEventLogger] persist failed strategy={} type={}: {}",
                    strategyId, eventType, ex.getMessage());
        }
    }
}

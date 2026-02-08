package com.vertex.service.strategy.engine;

import com.vertex.model.entity.quote.KLine;
import com.vertex.service.quote.event.KLineEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * K线事件监听器
 * <p>
 * 实时监听 KLineEvent，按 exchange:symbol:interval 分组后触发策略评估。
 * 使用 @Async 避免阻塞K线数据写入流程。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StrategyEventListener {

    private final StrategyEngineService engineService;

    @Async
    @EventListener
    public void onKLineUpdate(KLineEvent event) {
        List<KLine> klines = event.getKlines();
        if (klines == null || klines.isEmpty()) {
            return;
        }

        // 按 exchange:symbol:interval 分组
        Map<String, List<KLine>> grouped = klines.stream()
                .collect(Collectors.groupingBy(
                        k -> k.getExchange() + ":" + k.getSymbol() + ":" + k.getInterval().getCode()
                ));

        for (Map.Entry<String, List<KLine>> entry : grouped.entrySet()) {
            KLine sample = entry.getValue().get(0);
            try {
                engineService.processKLineUpdate(
                        sample.getExchange(),
                        sample.getSymbol(),
                        sample.getInterval(),
                        entry.getValue()
                );
            } catch (Exception e) {
                log.error("Strategy processing failed for {}: {}", entry.getKey(), e.getMessage());
            }
        }
    }
}

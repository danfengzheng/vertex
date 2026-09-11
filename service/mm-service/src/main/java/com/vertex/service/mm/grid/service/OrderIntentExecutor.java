package com.vertex.service.mm.grid.service;

import com.vertex.service.mm.binance.MmOrderRequest;
import com.vertex.service.mm.binance.MmOrderResult;
import com.vertex.service.mm.grid.entity.GridStrategy;
import com.vertex.service.mm.grid.mapper.GridStrategyMapper;
import com.vertex.service.mm.grid.entity.GridOrder;
import com.vertex.service.mm.grid.mapper.GridOrderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 把 SlidingWindowManager 输出的 {@link GridOrderIntent} 批量落实为币安下单。
 * <p>
 * 职责：
 * <ul>
 *   <li>按 5 单/批打包 place（币安 batchOrders 上限）</li>
 *   <li>按 10 单/批打包 cancel</li>
 *   <li>下单成功后写 grid_order + 回填 SlidingWindow 的 slot 记录</li>
 *   <li>GTX 被拒时不重试，记录 grid_event 让 GridEngine 决策</li>
 * </ul>
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderIntentExecutor {

    private final GridOrderMapper gridOrderMapper;
    private final SlidingWindowManager slidingWindow;
    private final GridEventLogger eventLogger;
    private final OrderClientRouter router;
    private final GridStrategyMapper strategyMapper;

    /**
     * @param intents 混合 PLACE + CANCEL 意图
     * @param apiKey / apiSecret 账户凭证（业务层传入）
     * @return 执行结果统计
     */
    public ExecutionSummary execute(List<GridOrderIntent> intents, String apiKey, String apiSecret) {
        List<GridOrderIntent> places = new ArrayList<>();
        List<GridOrderIntent> cancels = new ArrayList<>();
        for (GridOrderIntent i : intents) {
            if (i.getActionType() == GridOrderIntent.ActionType.PLACE) places.add(i);
            else cancels.add(i);
        }

        ExecutionSummary summary = new ExecutionSummary();

        // ── 撤单先执行 ──────────────────────────────
        Map<Long, List<GridOrderIntent>> cancelsBySymbol = new HashMap<>();
        for (GridOrderIntent c : cancels) {
            cancelsBySymbol.computeIfAbsent(c.getStrategyId(), k -> new ArrayList<>()).add(c);
        }
        cancelsBySymbol.forEach((strategyId, list) -> {
            GridStrategy strategy = strategyMapper.selectById(strategyId);
            for (int i = 0; i < list.size(); i += 10) {
                List<GridOrderIntent> batch = list.subList(i, Math.min(i + 10, list.size()));
                List<Long> ids = batch.stream().map(GridOrderIntent::getExchangeOrderId).toList();
                String symbol = batch.get(0).getSymbol();
                try {
                    List<Boolean> results = router.cancelBatchOrders(strategy, apiKey, apiSecret, symbol, ids);
                    for (int j = 0; j < results.size(); j++) {
                        if (Boolean.TRUE.equals(results.get(j))) {
                            markCanceled(batch.get(j).getExchangeOrderId());
                            summary.cancelSuccess++;
                        } else {
                            summary.cancelFailed++;
                        }
                    }
                } catch (Exception ex) {
                    log.warn("[OrderExec] cancel batch error strategy={}: {}", strategyId, ex.getMessage());
                    summary.cancelFailed += batch.size();
                }
            }
        });

        // ── 挂单 5 单/批 ────────────────────────────
        Map<Long, List<GridOrderIntent>> placesBySymbol = new HashMap<>();
        for (GridOrderIntent p : places) {
            placesBySymbol.computeIfAbsent(p.getStrategyId(), k -> new ArrayList<>()).add(p);
        }
        placesBySymbol.forEach((strategyId, list) -> {
            GridStrategy strategy = strategyMapper.selectById(strategyId);
            for (int i = 0; i < list.size(); i += 5) {
                List<GridOrderIntent> batch = list.subList(i, Math.min(i + 5, list.size()));
                List<MmOrderRequest> requests = new ArrayList<>(batch.size());
                for (GridOrderIntent intent : batch) {
                    if (intent.getClientOrderId() == null || intent.getClientOrderId().isBlank()) {
                        intent.setClientOrderId("mm-" + strategyId + "-" + UUID.randomUUID().toString().substring(0, 12));
                    }
                    requests.add(MmOrderRequest.builder()
                            .symbol(intent.getSymbol())
                            .side(intent.getSide())
                            .positionSide(intent.getPositionSide())
                            .price(intent.getPrice())
                            .quantity(intent.getQty())
                            .clientOrderId(intent.getClientOrderId())
                            .build());
                }
                try {
                    List<MmOrderResult> results = router.placeBatchOrders(strategy, apiKey, apiSecret, requests);
                    for (int j = 0; j < results.size(); j++) {
                        MmOrderResult r = results.get(j);
                        GridOrderIntent intent = batch.get(j);
                        if (r.isSuccess()) {
                            persistOrder(intent, r);
                            // 只对 OPEN 网格单登记到 slot（CLOSE 单价格是 fillPrice±step，不在网格上）
                            if ("OPEN".equalsIgnoreCase(intent.getRole())) {
                                slidingWindow.recordOrderPlaced(intent.getStrategyId(), intent.getPrice(),
                                        intent.getPositionSide(), r.getExchangeOrderId(), r.getClientOrderId());
                            }
                            summary.placeSuccess++;
                        } else if (r.isGtxRejected()) {
                            Map<String, Object> payload = new HashMap<>();
                            payload.put("price", intent.getPrice());
                            payload.put("side", intent.getSide());
                            payload.put("positionSide", intent.getPositionSide());
                            payload.put("errorCode", r.getErrorCode());
                            payload.put("errorMessage", r.getErrorMessage());
                            eventLogger.warn(intent.getStrategyId(), "GTX_REJECTED", payload);
                            summary.gtxRejected++;
                        } else {
                            log.warn("[OrderExec] place failed strategy={} price={} err={}",
                                    intent.getStrategyId(), intent.getPrice(), r.getErrorMessage());
                            summary.placeFailed++;
                        }
                    }
                } catch (Exception ex) {
                    log.error("[OrderExec] place batch error strategy={}: {}", strategyId, ex.getMessage(), ex);
                    summary.placeFailed += batch.size();
                }
            }
        });

        return summary;
    }

    private void persistOrder(GridOrderIntent intent, MmOrderResult result) {
        try {
            GridOrder o = new GridOrder();
            o.setGridStrategyId(intent.getStrategyId());
            o.setExchangeOrderId(String.valueOf(result.getExchangeOrderId()));
            o.setClientOrderId(result.getClientOrderId());
            o.setSide(intent.getSide());
            o.setPositionSide(intent.getPositionSide());
            o.setRole(intent.getRole());
            o.setGridIndex(intent.getGridIndex());
            o.setGridPrice(intent.getPrice());
            o.setQty(intent.getQty());
            o.setStatus("LIVE");
            o.setPlacedAt(LocalDateTime.now());
            gridOrderMapper.insert(o);
        } catch (Exception ex) {
            // grid_order 记录缺失是严重问题 —— WsReconciliation 之后会把它标为 UNKNOWN 但归属丢失
            // 必须打事件日志让告警渠道能感知
            log.error("[OrderExec] persistOrder FAILED strategy={} clientOrderId={} exchangeId={}: {}",
                    intent.getStrategyId(), result.getClientOrderId(),
                    result.getExchangeOrderId(), ex.getMessage(), ex);
            java.util.Map<String, Object> payload = new java.util.HashMap<>();
            payload.put("clientOrderId", result.getClientOrderId());
            payload.put("exchangeOrderId", result.getExchangeOrderId());
            payload.put("price", intent.getPrice());
            payload.put("error", ex.getMessage());
            eventLogger.error(intent.getStrategyId(), "PERSIST_ORDER_FAILED", payload);
        }
    }

    private void markCanceled(Long exchangeOrderId) {
        try {
            com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<GridOrder> w =
                    new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<>();
            w.eq(GridOrder::getExchangeOrderId, String.valueOf(exchangeOrderId))
             .set(GridOrder::getStatus, "CANCELED")
             .set(GridOrder::getCanceledAt, LocalDateTime.now());
            gridOrderMapper.update(null, w);
        } catch (Exception ignored) {}
    }

    public static class ExecutionSummary {
        public int placeSuccess = 0;
        public int placeFailed = 0;
        public int gtxRejected = 0;
        public int cancelSuccess = 0;
        public int cancelFailed = 0;

        @Override public String toString() {
            return String.format("place=%d/%d gtx_rej=%d cancel=%d/%d",
                    placeSuccess, placeSuccess + placeFailed, gtxRejected,
                    cancelSuccess, cancelSuccess + cancelFailed);
        }
    }
}

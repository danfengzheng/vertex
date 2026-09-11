package com.vertex.service.mm.paper;

import com.vertex.service.mm.binance.MmOrderRequest;
import com.vertex.service.mm.binance.MmOrderResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Paper 模式下的下单实现 —— 存入 PaperOrderBook，模拟 GTX 拒绝率。
 * <p>
 * 接口与 MmOrderClient 对齐（供 OrderClientRouter 无缝切换）。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaperOrderClient {

    private final PaperOrderBook orderBook;

    /** 每 symbol 的模拟 GTX 拒绝率 */
    private final java.util.concurrent.ConcurrentMap<String, BigDecimal> gtxRejectRate =
            new java.util.concurrent.ConcurrentHashMap<>();

    public void setGtxRejectRate(String symbol, BigDecimal rate) {
        gtxRejectRate.put(symbol.toUpperCase(), rate);
    }

    public MmOrderResult placeOrder(MmOrderRequest req) {
        BigDecimal rejectRate = gtxRejectRate.getOrDefault(req.getSymbol().toUpperCase(),
                new BigDecimal("0.05"));
        if (rejectRate.compareTo(BigDecimal.ZERO) > 0
                && ThreadLocalRandom.current().nextDouble() < rejectRate.doubleValue()) {
            log.debug("[PaperOrder] simulated GTX reject: {} {} @ {}",
                    req.getSymbol(), req.getSide(), req.getPrice());
            return MmOrderResult.builder()
                    .success(false)
                    .clientOrderId(req.getClientOrderId())
                    .errorCode(-5022)
                    .errorMessage("[PAPER] simulated GTX reject")
                    .gtxRejected(true)
                    .build();
        }

        long paperOrderId = orderBook.nextOrderId();
        PaperOrder order = PaperOrder.builder()
                .paperOrderId(paperOrderId)
                .clientOrderId(req.getClientOrderId() != null ? req.getClientOrderId()
                        : "paper-" + UUID.randomUUID().toString().substring(0, 12))
                .symbol(req.getSymbol())
                .side(req.getSide())
                .positionSide(req.getPositionSide())
                .price(req.getPrice())
                .quantity(req.getQuantity())
                .filledQty(BigDecimal.ZERO)
                .placedTimeMs(System.currentTimeMillis())
                .queueAheadQty(orderBook.queueQtyAtPrice(req.getSymbol(), req.getPrice(), req.getSide()))
                .status("NEW")
                .build();
        orderBook.place(order);

        return MmOrderResult.builder()
                .success(true)
                .exchangeOrderId(paperOrderId)
                .clientOrderId(order.getClientOrderId())
                .status("NEW")
                .build();
    }

    public List<MmOrderResult> placeBatchOrders(List<MmOrderRequest> requests) {
        if (requests == null || requests.isEmpty()) return List.of();
        List<MmOrderResult> results = new ArrayList<>(requests.size());
        for (MmOrderRequest r : requests) results.add(placeOrder(r));
        return results;
    }

    public boolean cancelOrder(String symbol, Long paperOrderId) {
        return orderBook.cancel(paperOrderId);
    }

    public List<Boolean> cancelBatchOrders(String symbol, List<Long> ids) {
        List<Boolean> out = new ArrayList<>(ids.size());
        for (Long id : ids) out.add(orderBook.cancel(id));
        return out;
    }

    /**
     * Paper 模式模拟市价单：立即以 last aggTrade 价成交（简化 —— 忽略滑点）。
     * <p>
     * 用于 emergency market fallback / proactive reducer escape hatch。
     * 因为不走 orderBook 撮合队列，直接按传入 qty 全额成交。
     * </p>
     */
    public MmOrderResult placeMarketOrder(String symbol, String side, String positionSide,
                                           BigDecimal qty, String clientOrderId) {
        long paperOrderId = orderBook.nextOrderId();
        // 简化：MARKET 单立即视为成交（模拟不填价，直接使用最新市场价由上层处理）
        log.warn("[PaperOrder] MARKET simulated fill: {} {} {} qty={}", symbol, side, positionSide, qty);
        return MmOrderResult.builder()
                .success(true)
                .exchangeOrderId(paperOrderId)
                .clientOrderId(clientOrderId != null ? clientOrderId
                        : "paper-mkt-" + UUID.randomUUID().toString().substring(0, 12))
                .status("FILLED")
                .executedQty(qty)
                .build();
    }
}

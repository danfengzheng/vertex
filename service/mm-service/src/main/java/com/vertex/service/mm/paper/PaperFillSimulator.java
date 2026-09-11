package com.vertex.service.mm.paper;

import com.vertex.service.mm.market.AggTrade;
import com.vertex.service.mm.market.AggTradeListener;
import com.vertex.service.mm.userstream.UserStreamListener;
import com.vertex.service.mm.userstream.event.OrderTradeUpdateEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Paper 模式成交模拟器。
 * <p>
 * 消费 aggTrade → 匹配 PaperOrderBook 里的挂单 → 发合成 ORDER_TRADE_UPDATE 事件。
 * </p>
 * <p>
 * <b>成交模型</b>（简化版）：
 * <ol>
 *   <li>收到 taker sell (@ price) → 触发 BUY book 里 price ≥ tradePrice 的所有挂单</li>
 *   <li>trade.qty × queue_loss_factor 是"我能吃到的量"（模拟排队损失）</li>
 *   <li>按价格优先 → 时间优先分配</li>
 *   <li>生成 ORDER_TRADE_UPDATE event → GridEngine 接收 → 触发补挂逻辑</li>
 * </ol>
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaperFillSimulator implements AggTradeListener {

    private final PaperOrderBook orderBook;
    private final PaperAccountState accountState;

    /** 转发给 UserStreamListener（模拟真实私有 WS 事件流）*/
    private final List<UserStreamListener> listeners = new CopyOnWriteArrayList<>();

    /** 每 symbol 的排队损失系数（PaperOrderClient 挂单时通过 config 传入）*/
    private final java.util.concurrent.ConcurrentMap<String, BigDecimal> queueLossFactorBySymbol =
            new java.util.concurrent.ConcurrentHashMap<>();

    public void addListener(UserStreamListener l) { listeners.add(l); }
    public void setQueueLossFactor(String symbol, BigDecimal factor) {
        queueLossFactorBySymbol.put(symbol.toUpperCase(), factor);
    }

    @Override
    public void onAggTrade(AggTrade trade) {
        if (trade == null || trade.getSymbol() == null) return;

        BigDecimal loss = queueLossFactorBySymbol.getOrDefault(trade.getSymbol().toUpperCase(),
                new BigDecimal("0.5"));
        BigDecimal availableFillQty = trade.getQty().multiply(loss);
        if (availableFillQty.compareTo(BigDecimal.ZERO) <= 0) return;

        // makerIsSeller=true: taker BUY 打上 → 匹配 maker SELL 单（价格 ≤ tradePrice）
        // makerIsSeller=false: taker SELL 打下 → 匹配 maker BUY 单（价格 ≥ tradePrice）
        String makerSide = trade.isMakerIsSeller() ? "SELL" : "BUY";
        List<PaperOrder> candidates = orderBook.ordersThatWouldFillAt(
                trade.getSymbol(), trade.getPrice(), makerSide);
        if (candidates.isEmpty()) {
            log.trace("[PaperFill] no candidates for trade {} {} @ {} qty={}",
                    trade.getSymbol(), makerSide, trade.getPrice(), trade.getQty());
            return;
        }
        log.debug("[PaperFill] {} taker {} @ {} qty={} → {} candidates, availableFill={}",
                trade.getSymbol(), trade.isMakerIsSeller() ? "BUY" : "SELL",
                trade.getPrice(), trade.getQty(), candidates.size(), availableFillQty);

        BigDecimal remainingTakerQty = availableFillQty;
        for (PaperOrder order : candidates) {
            if (remainingTakerQty.compareTo(BigDecimal.ZERO) <= 0) break;
            BigDecimal unfilled = order.getQuantity()
                    .subtract(order.getFilledQty() == null ? BigDecimal.ZERO : order.getFilledQty());
            if (unfilled.compareTo(BigDecimal.ZERO) <= 0) continue;

            BigDecimal fillNow = unfilled.min(remainingTakerQty);
            // Maker 按 MY 挂单价成交，不是 taker 的 print 价（真实撮合语义：more-passive-price wins）
            fillOrder(order, fillNow, order.getPrice(), trade.getTimeMs());
            remainingTakerQty = remainingTakerQty.subtract(fillNow);
        }
    }

    private void fillOrder(PaperOrder order, BigDecimal fillQty, BigDecimal fillPrice, long timeMs) {
        BigDecimal previouslyFilled = order.getFilledQty() == null ? BigDecimal.ZERO : order.getFilledQty();
        BigDecimal newFilled = previouslyFilled.add(fillQty);
        order.setFilledQty(newFilled);
        order.setLastFillTimeMs(timeMs);
        boolean fullyFilled = newFilled.compareTo(order.getQuantity()) >= 0;
        order.setStatus(fullyFilled ? "FILLED" : "PARTIALLY_FILLED");
        log.info("[PaperFill] {} {} {} @ {} qty={} status={} clientOrderId={}",
                order.getSymbol(), order.getPositionSide(), order.getSide(),
                order.getPrice(), fillQty, order.getStatus(), order.getClientOrderId());

        // 更新 paper account 持仓（只对 OPEN 单更新；CLOSE 单是减仓）
        // 这里简化：LONG BUY / SHORT SELL 是 OPEN；反向即 CLOSE
        boolean isOpen = ("LONG".equalsIgnoreCase(order.getPositionSide()) && "BUY".equalsIgnoreCase(order.getSide()))
                     || ("SHORT".equalsIgnoreCase(order.getPositionSide()) && "SELL".equalsIgnoreCase(order.getSide()));
        BigDecimal delta = isOpen ? fillQty : fillQty.negate();

        if (fullyFilled) {
            orderBook.cancel(order.getPaperOrderId());   // 从簿里移除
        }

        // 生成合成 ORDER_TRADE_UPDATE 事件
        OrderTradeUpdateEvent evt = OrderTradeUpdateEvent.builder()
                .eventTimeMs(timeMs)
                .transactionTimeMs(timeMs)
                .symbol(order.getSymbol())
                .exchangeOrderId(order.getPaperOrderId())
                .clientOrderId(order.getClientOrderId())
                .side(order.getSide())
                .positionSide(order.getPositionSide())
                .type("LIMIT")
                .timeInForce("GTX")
                .originalQty(order.getQuantity())
                .originalPrice(order.getPrice())
                .lastFilledQty(fillQty)
                .lastFilledPrice(fillPrice)
                .cumulativeFilledQty(newFilled)
                .cumulativeQuote(newFilled.multiply(fillPrice).setScale(8, RoundingMode.HALF_UP))
                .orderStatus(fullyFilled ? "FILLED" : "PARTIALLY_FILLED")
                .execType("TRADE")
                .fee(BigDecimal.ZERO)   // Paper 假设 0 fee (maker)
                .feeAsset("USDC")
                .maker(true)
                .build();

        for (UserStreamListener l : listeners) {
            try { l.onOrderUpdate(evt); } catch (Exception ex) {
                log.warn("[PaperFill] listener error: {}", ex.getMessage());
            }
        }
    }
}

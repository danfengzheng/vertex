package com.vertex.service.mm.grid.service;

import com.vertex.service.mm.binance.MmOrderClient;
import com.vertex.service.mm.binance.MmOrderRequest;
import com.vertex.service.mm.binance.MmOrderResult;
import com.vertex.service.mm.binance.SymbolFilters;
import com.vertex.service.mm.binance.SymbolFiltersCache;
import com.vertex.service.mm.grid.entity.GridStrategy;
import com.vertex.service.mm.market.SmartPricePlacer;
import com.vertex.service.mm.paper.PaperOrderClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 下单路由：根据 strategy.execution_mode 分发到 PaperOrderClient (PAPER) 或 MmOrderClient (LIVE)。
 * <p>
 * 同时支持 SmartPricePlacer 挂单价调整（PAPER + LIVE 都可用）。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderClientRouter {

    private final MmOrderClient liveClient;
    private final PaperOrderClient paperClient;
    private final SmartPricePlacer smartPricer;
    private final SymbolFiltersCache filtersCache;

    public boolean isPaper(GridStrategy strategy) {
        return "PAPER".equalsIgnoreCase(strategy.getExecutionMode());
    }

    /**
     * 应用 SmartPricePlacer（如果启用）。
     */
    private void applySmartPricing(GridStrategy strategy, List<MmOrderRequest> requests) {
        if (strategy.getPaperSmartPricingEnabled() == null
                || strategy.getPaperSmartPricingEnabled() != 1) return;

        BigDecimal minLiq = strategy.getPaperMinLiquidityQty();
        for (MmOrderRequest r : requests) {
            BigDecimal newPrice = minLiq != null
                    ? smartPricer.adjust(r.getSymbol(), r.getPrice(), r.getSide(), minLiq)
                    : smartPricer.adjust(r.getSymbol(), r.getPrice(), r.getSide());
            if (newPrice.compareTo(r.getPrice()) != 0) {
                r.setPrice(newPrice);
            }
        }
    }

    /**
     * 最终 tick/step 对齐 + 校验（safety net）。
     * <p>
     * 上游各生成器（SlidingWindow/Ladder/Reducer/Emergency）已应用规则；这里再对齐一次
     * 覆盖 SmartPricePlacer 可能引入的偏差，以及任何未来漏改的路径。
     * </p>
     */
    private void normalize(List<MmOrderRequest> requests) {
        for (MmOrderRequest r : requests) {
            SymbolFilters f = filtersCache.get(r.getSymbol());
            if (f == null) continue;
            BigDecimal rp = f.roundPrice(r.getPrice(), r.getSide());
            BigDecimal rq = f.roundQty(r.getQuantity());
            r.setPrice(rp);
            r.setQuantity(rq);
            String err = f.validate(rp, rq);
            if (err != null) {
                log.warn("[Router] {} order violates filter after normalize: {} — will submit anyway",
                        r.getSymbol(), err);
            }
        }
    }

    /**
     * 单笔挂单路由。
     */
    public MmOrderResult placeOrder(GridStrategy strategy, String apiKey, String apiSecret, MmOrderRequest req) {
        List<MmOrderRequest> one = new ArrayList<>(1);
        one.add(req);
        applySmartPricing(strategy, one);
        normalize(one);
        if (isPaper(strategy)) return paperClient.placeOrder(req);
        return liveClient.placeOrder(apiKey, apiSecret, req);
    }

    /**
     * batch 挂单路由。
     */
    public List<MmOrderResult> placeBatchOrders(GridStrategy strategy, String apiKey, String apiSecret,
                                                 List<MmOrderRequest> reqs) {
        applySmartPricing(strategy, reqs);
        normalize(reqs);
        if (isPaper(strategy)) return paperClient.placeBatchOrders(reqs);
        return liveClient.placeBatchOrders(apiKey, apiSecret, reqs);
    }

    public boolean cancelOrder(GridStrategy strategy, String apiKey, String apiSecret,
                                String symbol, Long orderId) {
        if (isPaper(strategy)) return paperClient.cancelOrder(symbol, orderId);
        return liveClient.cancelOrder(apiKey, apiSecret, symbol, orderId);
    }

    public List<Boolean> cancelBatchOrders(GridStrategy strategy, String apiKey, String apiSecret,
                                            String symbol, List<Long> orderIds) {
        if (isPaper(strategy)) return paperClient.cancelBatchOrders(symbol, orderIds);
        return liveClient.cancelBatchOrders(apiKey, apiSecret, symbol, orderIds);
    }

    /**
     * 市价单路由（emergency / escape hatch 专用）。
     * Paper 模式模拟立即成交；Live 模式真发币安 MARKET 单（收 taker 手续费！）。
     */
    public MmOrderResult placeMarketOrder(GridStrategy strategy, String apiKey, String apiSecret,
                                           String symbol, String side, String positionSide,
                                           BigDecimal qty, String clientOrderId) {
        BigDecimal normQty = filtersCache.roundQty(symbol, qty);
        if (isPaper(strategy)) return paperClient.placeMarketOrder(symbol, side, positionSide, normQty, clientOrderId);
        return liveClient.placeMarketOrder(apiKey, apiSecret, symbol, side, positionSide, normQty, clientOrderId);
    }
}

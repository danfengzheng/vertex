package com.vertex.service.order.task;

import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.vertex.model.entity.strategy.Strategy;
import com.vertex.model.entity.trading.ExecutionMode;
import com.vertex.model.entity.trading.MarketType;
import com.vertex.model.entity.trading.Order;
import com.vertex.model.entity.trading.OrderSide;
import com.vertex.model.entity.trading.OrderStatus;
import com.vertex.model.entity.trading.PositionSide;
import com.vertex.service.order.client.BinanceFuturesClient;
import com.vertex.service.order.mapper.OrderMapper;
import com.vertex.service.order.mapper.StrategyRefMapper;
import com.vertex.service.order.service.impl.ExchangeAccountServiceImpl;
import com.vertex.service.order.service.impl.PositionManagementService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * 合约挂单成交轮询任务
 * <p>
 * 每 15 秒扫描所有 SUBMITTED 状态的合约实盘订单（即 Binance 返回 NEW 的 GTC 限价单）：
 * 1. 向 Binance 查询最新订单状态
 * 2. 若已成交（FILLED / PARTIALLY_FILLED），更新订单记录并创建或更新持仓
 * 3. 若已撤销或被拒绝，标记为 CANCELLED / REJECTED
 * </p>
 * <p>
 * 背景：applySlippageProtection 会将 MARKET 单转换为限价单以保护滑点，
 * 该限价单理论上应立即成交，但极端行情下可能以 NEW 状态挂单。
 * 本任务作为兜底，确保成交后持仓数据能被正确记录。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "vertex.trading", name = "enabled", havingValue = "true")
public class FuturesOrderFillTask {

    private final OrderMapper orderMapper;
    private final BinanceFuturesClient binanceFuturesClient;
    private final PositionManagementService positionManagementService;
    private final ExchangeAccountServiceImpl accountService;
    private final StrategyRefMapper strategyRefMapper;

    /**
     * 手动同步指定订单的 Binance 成交状态，并补录持仓。
     * 供 Controller 调用，无需等待定时任务触发。
     *
     * @param orderId 系统订单 ID
     * @throws IllegalArgumentException 订单不存在或不满足同步条件
     */
    public void syncOrderById(Long orderId) {
        Order order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new IllegalArgumentException("订单不存在: " + orderId);
        }
        if (order.getStatus() != OrderStatus.SUBMITTED) {
            throw new IllegalArgumentException("订单状态为 " + order.getStatus() + "，仅 SUBMITTED 状态可同步");
        }
        if (order.getExchangeOrderId() == null) {
            throw new IllegalArgumentException("订单尚未提交到交易所，无 exchangeOrderId");
        }
        if (order.getMarketType() == null || !order.getMarketType().isFutures()) {
            throw new IllegalArgumentException("仅支持合约订单（USDM/COINM）");
        }
        log.info("[FillPoller] Manual sync triggered for order {}", orderId);
        pollSingleOrder(order);
    }

    @Scheduled(fixedDelay = 15000)
    public void pollSubmittedFuturesOrders() {
        // 查询所有 SUBMITTED 状态的合约实盘订单（有 exchangeOrderId 说明已提交到 Binance）
        List<Order> orders = orderMapper.selectList(new LambdaQueryWrapper<Order>()
                .eq(Order::getStatus, OrderStatus.SUBMITTED)
                .eq(Order::getTradeMode, ExecutionMode.LIVE)
                .in(Order::getMarketType, MarketType.USDM, MarketType.COINM)
                .isNotNull(Order::getExchangeOrderId)
                .eq(Order::getDeleted, 0));

        if (orders.isEmpty()) {
            return;
        }

        log.debug("[FillPoller] Checking {} submitted futures orders", orders.size());

        for (Order order : orders) {
            try {
                pollSingleOrder(order);
            } catch (Exception e) {
                log.error("[FillPoller] Error polling order {}: {}", order.getId(), e.getMessage(), e);
            }
        }
    }

    private void pollSingleOrder(Order order) {
        String[] credentials = accountService.getDecryptedCredentials(order.getAccountId());
        String apiKey    = credentials[0];
        String apiSecret = credentials[1];
        MarketType marketType = order.getMarketType();

        JSONObject result = binanceFuturesClient.queryOrder(
                apiKey, apiSecret, order.getSymbol(), order.getExchangeOrderId(), marketType);

        String status = result.getString("status");

        if ("FILLED".equals(status) || "PARTIALLY_FILLED".equals(status)) {
            processFill(order, result, status);
        } else if ("CANCELED".equals(status) || "EXPIRED".equals(status) || "REJECTED".equals(status)) {
            order.setStatus(OrderStatus.CANCELLED);
            orderMapper.updateById(order);
            log.info("[FillPoller] Order {} marked CANCELLED, Binance status={}", order.getId(), status);
        } else {
            // 仍为 NEW / PENDING_CANCEL 等，继续等待
            log.debug("[FillPoller] Order {} still pending, Binance status={}", order.getId(), status);
        }
    }

    private void processFill(Order order, JSONObject result, String binanceStatus) {
        // 提取成交数量和均价
        BigDecimal executedQty = result.getBigDecimal("executedQty");
        BigDecimal avgPrice    = result.getBigDecimal("avgPrice");

        // avgPrice 为 0 时从 cumQuote 计算
        if ((avgPrice == null || avgPrice.compareTo(BigDecimal.ZERO) == 0)
                && executedQty != null && executedQty.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal cumQuote = result.getBigDecimal("cumQuote");
            if (cumQuote != null && cumQuote.compareTo(BigDecimal.ZERO) > 0) {
                avgPrice = cumQuote.divide(executedQty, 10, RoundingMode.HALF_UP);
            }
        }

        if (executedQty == null || avgPrice == null
                || executedQty.compareTo(BigDecimal.ZERO) <= 0
                || avgPrice.compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("[FillPoller] Order {} FILLED but missing qty/price, skipping", order.getId());
            return;
        }

        order.setFilledQuantity(executedQty);
        order.setFilledPrice(avgPrice);
        order.setFee(BigDecimal.ZERO);           // 合约手续费单独扣，不计入数量
        order.setStatus("FILLED".equals(binanceStatus) ? OrderStatus.FILLED : OrderStatus.PARTIALLY_FILLED);

        // ── 推断 reduceOnly ──────────────────────────────────────────────────────
        // reduceOnly 未持久化到 DB，需根据是否存在对应方向的持仓来判断：
        //   SELL + 存在 LONG 持仓 → 平多（reduceOnly=true）
        //   BUY  + 存在 SHORT 持仓 → 平空（reduceOnly=true）
        //   否则 → 开仓（reduceOnly=false）
        boolean reduceOnly = false;
        if (order.getSide() == OrderSide.SELL) {
            reduceOnly = positionManagementService.findOpenPosition(
                    order.getStrategyId(), order.getAccountId(),
                    order.getExchange(), order.getSymbol(), PositionSide.LONG) != null;
        } else if (order.getSide() == OrderSide.BUY) {
            reduceOnly = positionManagementService.findOpenPosition(
                    order.getStrategyId(), order.getAccountId(),
                    order.getExchange(), order.getSymbol(), PositionSide.SHORT) != null;
        }
        order.setReduceOnly(reduceOnly);

        // 开仓时从策略读取杠杆和保证金模式，写入持仓
        if (!reduceOnly && order.getStrategyId() != null) {
            Strategy strategy = strategyRefMapper.selectById(order.getStrategyId());
            if (strategy != null) {
                order.setLeverage(strategy.getLeverage());
                order.setMarginType(strategy.getMarginType());
            }
        }

        // 更新订单记录
        orderMapper.updateById(order);

        // 创建/更新持仓
        positionManagementService.updatePosition(order);

        log.info("[FillPoller] Order {} processed: side={} qty={} avgPrice={} reduceOnly={}",
                order.getId(), order.getSide(), executedQty, avgPrice, reduceOnly);
    }
}

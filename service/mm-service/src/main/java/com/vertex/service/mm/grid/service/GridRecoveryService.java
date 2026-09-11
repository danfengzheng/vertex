package com.vertex.service.mm.grid.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.vertex.service.mm.grid.entity.GridOrder;
import com.vertex.service.mm.grid.entity.GridStrategy;
import com.vertex.service.mm.grid.mapper.GridOrderMapper;
import com.vertex.service.mm.grid.mapper.GridStrategyMapper;
import com.vertex.service.mm.market.BookTickerSubscriber;
import com.vertex.service.mm.market.DepthSubscriber;
import com.vertex.service.mm.userstream.UserDataStreamManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * JVM 重启后自动恢复 RUNNING 策略的内存状态。
 * <p>
 * 场景：进程崩溃 / 部署重启 / kill -9 后，DB 里策略仍 status=RUNNING，
 * 但 {@link SlidingWindowManager} 内存状态、WS 订阅、UserDataStream 连接全部丢失。
 * 不恢复的话所有 scheduler 都会 no-op（state==null 直接 return）而不报错，
 * 用户不知情继续以为在跑，可能几小时后才发现。
 * </p>
 *
 * <b>恢复策略</b>：
 * <ul>
 *   <li><b>LIVE</b>：重建 SlidingWindow 状态 → 从 grid_order 表回填每档挂单 ID →
 *       订阅市场数据 → 启动 UserDataStream → 注册 listener</li>
 *   <li><b>PAPER</b>：{@link com.vertex.service.mm.paper.PaperOrderBook} 是纯内存，
 *       重启后无法恢复历史 paper 单，状态改 STOPPED 让用户显式重启</li>
 * </ul>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "vertex.mm", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class GridRecoveryService {

    private final GridStrategyMapper strategyMapper;
    private final GridOrderMapper orderMapper;
    private final SlidingWindowManager slidingWindow;
    private final GridEngine gridEngine;
    private final GridEventLogger eventLogger;
    private final BookTickerSubscriber bookTickerSubscriber;
    private final DepthSubscriber depthSubscriber;
    private final AccountCredentialResolver credentialResolver;
    private final UserDataStreamManager userStreamManager;
    private final GridStrategyService strategyService;
    private final com.vertex.service.mm.binance.BinanceAccountClient binanceAccountClient;

    /**
     * 应用就绪后触发（在所有 bean 初始化完成后）。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void recoverAll() {
        List<GridStrategy> running = strategyMapper.selectList(new LambdaQueryWrapper<GridStrategy>()
                .eq(GridStrategy::getStatus, "RUNNING"));
        if (running.isEmpty()) {
            log.info("[Recovery] no RUNNING strategies to recover");
            return;
        }
        log.warn("[Recovery] found {} RUNNING strategies, attempting recovery after restart", running.size());

        int recoveredLive = 0;
        int stoppedPaper = 0;
        int failed = 0;
        for (GridStrategy s : running) {
            try {
                if ("PAPER".equalsIgnoreCase(s.getExecutionMode())) {
                    stopPaperStrategy(s);
                    stoppedPaper++;
                } else {
                    recoverLiveStrategy(s);
                    recoveredLive++;
                }
            } catch (Exception ex) {
                failed++;
                log.error("[Recovery] strategy={} recovery failed: {}", s.getId(), ex.getMessage(), ex);
                eventLogger.error(s.getId(), "RECOVERY_FAILED", Map.of("error", ex.getMessage()));
            }
        }
        log.warn("[Recovery] complete: live={} paperStopped={} failed={}",
                recoveredLive, stoppedPaper, failed);
    }

    /**
     * PAPER 策略不做恢复 —— 内存簿丢了，历史挂单永远填不回来。
     * <p>
     * <b>关键</b>：同时把 grid_order 里所有 LIVE 记录标为 CANCELED —— 这些订单在币安上根本不存在（PAPER），
     * 内存 PaperOrderBook 也已丢失，留着 status=LIVE 会导致下次手动启动时 UI 显示"挂单翻倍"的假象。
     * </p>
     */
    private void stopPaperStrategy(GridStrategy s) {
        int canceled = markLiveOrdersCanceled(s.getId(), "PAPER_RESTART");
        log.warn("[Recovery] PAPER strategy={} marked STOPPED, {} stale LIVE orders cleaned up",
                s.getId(), canceled);
        strategyMapper.update(null, new LambdaUpdateWrapper<GridStrategy>()
                .eq(GridStrategy::getId, s.getId())
                .set(GridStrategy::getStatus, "STOPPED"));
        eventLogger.warn(s.getId(), "RECOVERY_PAPER_STOPPED", Map.of(
                "reason", "PaperOrderBook cannot survive JVM restart",
                "staleOrdersCleaned", canceled));
    }

    /** 把某策略所有 status=LIVE 的 grid_order 标为 CANCELED，返回受影响行数 */
    private int markLiveOrdersCanceled(long strategyId, String reason) {
        try {
            com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<GridOrder> w =
                    new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<>();
            w.eq(GridOrder::getGridStrategyId, strategyId)
             .eq(GridOrder::getStatus, "LIVE")
             .set(GridOrder::getStatus, "CANCELED")
             .set(GridOrder::getCanceledAt, java.time.LocalDateTime.now());
            return orderMapper.update(null, w);
        } catch (Exception ex) {
            log.warn("[Recovery] markLiveOrdersCanceled strategy={} failed: {}", strategyId, ex.getMessage());
            return 0;
        }
    }

    /** LIVE 策略：重建状态 + 重连订阅（挂单本身还在币安上，无需重挂）*/
    private void recoverLiveStrategy(GridStrategy s) {
        // 1. 校验必要字段
        BigDecimal centerPrice = s.getCenterPrice();
        BigDecimal stepPct = s.getStepPct() != null ? s.getStepPct() : s.getBaseStepPct();
        if (centerPrice == null || stepPct == null) {
            throw new IllegalStateException("LIVE strategy missing centerPrice/stepPct in DB — cannot recover");
        }

        // 2. 重建 SlidingWindow 内存拓扑
        slidingWindow.rehydrate(s, centerPrice, stepPct);

        // 3. 从 grid_order 表回填每档挂单（LIVE 状态 = 在币安上仍活跃）
        List<GridOrder> liveOrders = orderMapper.selectList(new LambdaQueryWrapper<GridOrder>()
                .eq(GridOrder::getGridStrategyId, s.getId())
                .eq(GridOrder::getStatus, "LIVE"));
        int refilled = 0;
        for (GridOrder o : liveOrders) {
            if (o.getGridPrice() == null || o.getPositionSide() == null) continue;
            Long exchangeId = null;
            if (o.getExchangeOrderId() != null) {
                try { exchangeId = Long.parseLong(o.getExchangeOrderId()); }
                catch (NumberFormatException ignored) {}
            }
            slidingWindow.recordOrderPlaced(s.getId(), o.getGridPrice(),
                    o.getPositionSide(), exchangeId, o.getClientOrderId());
            if (o.getClientOrderId() != null) {
                gridEngine.trackClientOrder(o.getClientOrderId(), s.getId());
            }
            refilled++;
        }

        // 4. 重订市场数据 WS（refCount 幂等）
        bookTickerSubscriber.subscribe(s.getSymbol());
        depthSubscriber.subscribe(s.getSymbol());

        // 5. 启动私有 UserDataStream（同账户多策略只启一次，Manager 内部去重）
        var creds = credentialResolver.resolve(s.getAccountId());
        userStreamManager.start(s.getAccountId(), creds.apiKey());
        strategyService.ensureListenersRegistered();

        // 6. 拉持仓 → 塞回 state（避免等下次 ACCOUNT_UPDATE 才更新）
        int missedFills = 0;
        BigDecimal[] pos = binanceAccountClient.fetchPositionRisk(creds.apiKey(), creds.apiSecret(), s.getSymbol());
        if (pos != null) {
            var state = slidingWindow.getState(s.getId());
            if (state != null) {
                state.setCurrentLongQty(pos[0]);
                state.setCurrentShortQty(pos[1]);
            }
            // 对比内存 vs 币安真实持仓，检测重启期间丢失的 fill
            missedFills = detectMissedFills(s, pos[0], pos[1]);
        }

        // 7. 反向对账：币安有的 openOrders 我们本地没有 → 孤儿单告警（不主动撤，等人工确认）
        int orphans = detectOrphanOrders(s, creds.apiKey(), creds.apiSecret());

        Map<String, Object> payload = new HashMap<>();
        payload.put("center", centerPrice);
        payload.put("step", stepPct);
        payload.put("ordersRefilled", refilled);
        payload.put("realLongQty", pos == null ? null : pos[0]);
        payload.put("realShortQty", pos == null ? null : pos[1]);
        payload.put("orphanOrdersOnBinance", orphans);
        payload.put("missedFills", missedFills);
        eventLogger.warn(s.getId(), "RECOVERY_LIVE_OK", payload);
        log.warn("[Recovery] LIVE strategy={} recovered: center={} step={} orders={} orphan={} missedFills={} → WsReconciliation will validate further",
                s.getId(), centerPrice, stepPct, refilled, orphans, missedFills);
    }

    /**
     * 币安真实持仓 vs 内存中期望的持仓（= grid_order OPEN filled 未 pair）。
     * 差值 > 0 → 重启期间有 fill 事件丢失。差值以 qty 单位告警，不自动补 CLOSE（避免 double 挂）。
     */
    private int detectMissedFills(GridStrategy s, BigDecimal realLong, BigDecimal realShort) {
        try {
            List<GridOrder> filledOpens = orderMapper.selectList(new LambdaQueryWrapper<GridOrder>()
                    .eq(GridOrder::getGridStrategyId, s.getId())
                    .eq(GridOrder::getRole, "OPEN")
                    .in(GridOrder::getStatus, "FILLED", "PARTIAL")
                    .isNull(GridOrder::getPairOrderId));
            BigDecimal expLong = BigDecimal.ZERO, expShort = BigDecimal.ZERO;
            for (GridOrder o : filledOpens) {
                BigDecimal q = o.getFilledQty() != null ? o.getFilledQty() : o.getQty();
                if ("LONG".equalsIgnoreCase(o.getPositionSide())) expLong = expLong.add(q);
                else expShort = expShort.add(q);
            }
            BigDecimal deltaLong = realLong.subtract(expLong).abs();
            BigDecimal deltaShort = realShort.subtract(expShort).abs();
            BigDecimal tolerance = new BigDecimal("0.0001");
            int missed = 0;
            if (deltaLong.compareTo(tolerance) > 0) {
                log.warn("[Recovery] strategy={} LONG position mismatch: real={}, expected={}, delta={} (may indicate lost fill during restart)",
                        s.getId(), realLong, expLong, deltaLong);
                missed++;
            }
            if (deltaShort.compareTo(tolerance) > 0) {
                log.warn("[Recovery] strategy={} SHORT position mismatch: real={}, expected={}, delta={}",
                        s.getId(), realShort, expShort, deltaShort);
                missed++;
            }
            if (missed > 0) {
                eventLogger.error(s.getId(), "RECOVERY_POSITION_MISMATCH", Map.of(
                        "realLong", realLong, "expLong", expLong,
                        "realShort", realShort, "expShort", expShort,
                        "advice", "check grid_order table + Binance UI, may need manual CLOSE"));
            }
            return missed;
        } catch (Exception ex) {
            log.warn("[Recovery] detectMissedFills error: {}", ex.getMessage());
            return 0;
        }
    }

    /**
     * 反向对账：拉币安 openOrders → 找出 clientOrderId 前缀是 mm- 但本地 grid_order 里没有的孤儿单。
     * 不自动撤（可能是别的实例挂的），只告警等人工。
     */
    private int detectOrphanOrders(GridStrategy s, String apiKey, String apiSecret) {
        try {
            java.util.List<com.alibaba.fastjson2.JSONObject> remote =
                    binanceAccountClient.fetchOpenOrders(apiKey, apiSecret, s.getSymbol());
            if (remote.isEmpty()) return 0;
            java.util.Set<String> localClientIds = new java.util.HashSet<>();
            orderMapper.selectList(new LambdaQueryWrapper<GridOrder>()
                    .eq(GridOrder::getGridStrategyId, s.getId())
                    .eq(GridOrder::getStatus, "LIVE"))
                    .forEach(o -> { if (o.getClientOrderId() != null) localClientIds.add(o.getClientOrderId()); });
            int orphans = 0;
            for (var r : remote) {
                String cid = r.getString("clientOrderId");
                if (cid == null) continue;
                // 只关心我们自己前缀的单（不误报别的手动挂单）
                if (!cid.startsWith("mm-")) continue;
                if (!localClientIds.contains(cid)) {
                    orphans++;
                    log.warn("[Recovery] strategy={} orphan order on Binance: clientOrderId={} price={} qty={} side={} — local no record",
                            s.getId(), cid, r.get("price"), r.get("origQty"), r.get("side"));
                }
            }
            if (orphans > 0) {
                eventLogger.error(s.getId(), "RECOVERY_ORPHAN_ORDERS", Map.of(
                        "count", orphans,
                        "advice", "币安上有 mm-* 前缀单但本地无记录，可能是并发实例或数据丢失，请人工检查"));
            }
            return orphans;
        } catch (Exception ex) {
            log.warn("[Recovery] detectOrphanOrders error: {}", ex.getMessage());
            return 0;
        }
    }
}

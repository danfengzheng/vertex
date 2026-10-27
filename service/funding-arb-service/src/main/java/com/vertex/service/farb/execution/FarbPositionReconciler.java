package com.vertex.service.farb.execution;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.vertex.model.entity.trading.MarketType;
import com.vertex.service.farb.entity.FarbPosition;
import com.vertex.service.farb.entity.FarbStrategy;
import com.vertex.service.farb.mapper.FarbPositionMapper;
import com.vertex.service.farb.mapper.FarbStrategyMapper;
import com.vertex.service.farb.service.FarbEventLogger;
import com.vertex.service.order.client.BinanceFuturesClient;
import com.vertex.service.order.client.BinanceTradeClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 资金费套利 - 位置对账。
 * <p>
 * 每小时（LIVE 策略）对比：
 * <ul>
 *   <li>Binance 现货余额 vs SUM(OPEN pair.spotQty) per symbol</li>
 *   <li>Binance USDM 空仓 vs SUM(OPEN pair.perpQty) per symbol</li>
 * </ul>
 * 差值 > 1% 或绝对值 > 0.1 → 触发告警 + <b>暂停策略</b>（safety kill switch）。
 * </p>
 * <p>
 * 这是 dual-maker / partial fill / race 等复杂场景的"最终防线"。哪怕代码有漏 bug，
 * 对账 job 会在 1 小时内发现异常并停止扩大风险。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FarbPositionReconciler {

    private static final BigDecimal REL_TOLERANCE = new BigDecimal("0.01");   // 1%
    private static final BigDecimal ABS_TOLERANCE = new BigDecimal("0.1");    // 绝对差

    private final FarbStrategyMapper strategyMapper;
    private final FarbPositionMapper positionMapper;
    private final FarbCredentialResolver credResolver;
    private final BinanceTradeClient spotClient;
    private final BinanceFuturesClient futuresClient;
    private final FarbEventLogger eventLogger;

    /** 每小时 05 分对账（避开正点 funding 结算高峰）*/
    @Scheduled(cron = "0 5 * * * *")
    public void reconcileAll() {
        List<FarbStrategy> liveStrategies = strategyMapper.selectList(
                new LambdaQueryWrapper<FarbStrategy>()
                        .eq(FarbStrategy::getExecutionMode, "LIVE")
                        .eq(FarbStrategy::getStatus, "RUNNING")
                        .eq(FarbStrategy::getDeleted, 0));
        for (FarbStrategy s : liveStrategies) {
            try {
                reconcileOne(s);
            } catch (Exception ex) {
                log.error("[Reconciler] strategy={} failed: {}", s.getId(), ex.getMessage(), ex);
            }
        }
    }

    void reconcileOne(FarbStrategy strategy) {
        // 1) 拉本策略所有 OPEN pair，按 symbol 汇总 qty
        Map<String, BigDecimal> spotByPair = new HashMap<>();
        Map<String, BigDecimal> perpByPair = new HashMap<>();
        List<FarbPosition> openPairs = positionMapper.selectList(
                new LambdaQueryWrapper<FarbPosition>()
                        .eq(FarbPosition::getStrategyId, strategy.getId())
                        .eq(FarbPosition::getStatus, "OPEN"));
        for (FarbPosition p : openPairs) {
            spotByPair.merge(p.getSymbol(), nz(p.getSpotQty()), BigDecimal::add);
            perpByPair.merge(p.getSymbol(), nz(p.getPerpQty()), BigDecimal::add);
        }

        // 2) 解析凭证
        FarbCredentialResolver.Credentials spotCreds, perpCreds;
        try {
            spotCreds = credResolver.resolveSpotOrPaper(strategy.getSpotAccountId(), false);
            perpCreds = credResolver.resolvePerpOrPaper(strategy.getPerpAccountId(), false);
        } catch (Exception ex) {
            log.warn("[Reconciler] strategy={} credential failed: {}", strategy.getId(), ex.getMessage());
            return;
        }

        // 3) 拉 Binance 现货余额
        Map<String, BigDecimal> spotByBinance = fetchSpotBalances(spotCreds);
        // 4) 拉 Binance USDM 空仓
        Map<String, BigDecimal> perpByBinance = fetchPerpShortPositions(perpCreds);

        // 5) 对比每个 pair symbol
        boolean anyDrift = false;
        for (String sym : spotByPair.keySet()) {
            BigDecimal dbSpot = spotByPair.get(sym);
            String asset = sym.replace("USDT", "");
            BigDecimal onExchangeSpot = spotByBinance.getOrDefault(asset, BigDecimal.ZERO);
            if (drifted(dbSpot, onExchangeSpot)) {
                anyDrift = true;
                logDrift(strategy, sym, "spot", dbSpot, onExchangeSpot);
            }
        }
        for (String sym : perpByPair.keySet()) {
            BigDecimal dbPerp = perpByPair.get(sym);
            BigDecimal onExchangePerp = perpByBinance.getOrDefault(sym, BigDecimal.ZERO);
            if (drifted(dbPerp, onExchangePerp)) {
                anyDrift = true;
                logDrift(strategy, sym, "perp", dbPerp, onExchangePerp);
            }
        }

        // 6) 有 drift → 暂停策略
        if (anyDrift) {
            strategy.setStatus("PAUSED");
            strategyMapper.updateById(strategy);
            eventLogger.error(strategy.getId(), "RECONCILE_DRIFT_PAUSED",
                    Map.of("advice", "位置对账检测到 DB 与交易所差异，策略已自动暂停。请手动核对后重启。"));
            log.error("[Reconciler] strategy={} PAUSED due to position drift", strategy.getId());
        } else {
            log.info("[Reconciler] strategy={} OK, {} pair symbols reconciled",
                    strategy.getId(), spotByPair.size());
        }
    }

    // ─── Binance 侧数据拉取 ─────────────────────────────────────────────

    private Map<String, BigDecimal> fetchSpotBalances(FarbCredentialResolver.Credentials creds) {
        Map<String, BigDecimal> out = new HashMap<>();
        try {
            JSONObject acc = spotClient.getAccount(creds.apiKey(), creds.apiSecret());
            if (acc == null || !acc.containsKey("balances")) return out;
            JSONArray balances = acc.getJSONArray("balances");
            for (int i = 0; i < balances.size(); i++) {
                JSONObject b = balances.getJSONObject(i);
                BigDecimal free = b.getBigDecimal("free");
                BigDecimal locked = b.getBigDecimal("locked");
                BigDecimal total = (free != null ? free : BigDecimal.ZERO)
                        .add(locked != null ? locked : BigDecimal.ZERO);
                if (total.signum() > 0) {
                    out.put(b.getString("asset"), total);
                }
            }
        } catch (Exception ex) {
            log.warn("[Reconciler] fetch spot balances failed: {}", ex.getMessage());
        }
        return out;
    }

    private Map<String, BigDecimal> fetchPerpShortPositions(FarbCredentialResolver.Credentials creds) {
        Map<String, BigDecimal> out = new HashMap<>();
        try {
            JSONArray positions = futuresClient.getPositionRisk(
                    creds.apiKey(), creds.apiSecret(), null, MarketType.USDM);
            if (positions == null) return out;
            for (int i = 0; i < positions.size(); i++) {
                JSONObject p = positions.getJSONObject(i);
                BigDecimal amt = p.getBigDecimal("positionAmt");
                if (amt == null || amt.signum() >= 0) continue;   // 只关心空仓
                out.merge(p.getString("symbol"), amt.abs(), BigDecimal::add);
            }
        } catch (Exception ex) {
            log.warn("[Reconciler] fetch perp positions failed: {}", ex.getMessage());
        }
        return out;
    }

    // ─── 差异判定 ────────────────────────────────────────────────────

    /** 相对差 > 1% 或 绝对差 > 0.1 即视为漂移 */
    private boolean drifted(BigDecimal db, BigDecimal exchange) {
        BigDecimal diff = db.subtract(exchange).abs();
        if (diff.compareTo(ABS_TOLERANCE) <= 0) return false;   // 小于绝对容忍
        BigDecimal base = db.max(exchange);
        if (base.signum() == 0) return diff.signum() > 0;
        BigDecimal rel = diff.divide(base, 6, RoundingMode.HALF_UP);
        return rel.compareTo(REL_TOLERANCE) > 0;
    }

    private void logDrift(FarbStrategy s, String sym, String leg,
                           BigDecimal db, BigDecimal exchange) {
        BigDecimal diff = db.subtract(exchange);
        log.error("[Reconciler] DRIFT strategy={} sym={} leg={} db={} exchange={} diff={}",
                s.getId(), sym, leg, db, exchange, diff);
        eventLogger.error(s.getId(), "RECONCILE_DRIFT",
                Map.of("symbol", sym, "leg", leg,
                       "dbQty", db, "exchangeQty", exchange, "diff", diff));
    }

    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }
}

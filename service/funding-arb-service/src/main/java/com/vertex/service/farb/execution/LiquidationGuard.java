package com.vertex.service.farb.execution;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.vertex.service.farb.entity.FarbPosition;
import com.vertex.service.farb.entity.FarbStrategy;
import com.vertex.service.farb.mapper.FarbPositionMapper;
import com.vertex.service.farb.mapper.FarbStrategyMapper;
import com.vertex.service.farb.service.FarbEventLogger;
import com.vertex.service.farb.signal.FarbSignalCache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

/**
 * 资金费套利 - 强平保护。
 * <p>
 * Delta 中性策略里，spot 多 + perp 空的整体 PnL 接近 0，但 perp 独立保证金账户
 * 可能因单侧亏损被强平。价格拉升 (1/leverage)% 就到强平线（2x → 50%，3x → 33%）。
 * </p>
 * <p>
 * 本 Guard 每 30s 扫 OPEN pair：
 * <ul>
 *   <li>计算 perp 侧价格反向变动比例：(mark - entry_perp) / entry_perp</li>
 *   <li>如果超过安全阈值（strategy.liquidationSafeMovePct，默认 25%），触发主动平仓</li>
 *   <li>关闭原因 = LIQUIDATION_RISK，事件日志留档告警</li>
 * </ul>
 * 安全阈值应显著低于真实强平线（1/leverage），预留缓冲让平仓单能挂出去。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LiquidationGuard {

    private final FarbPositionMapper positionMapper;
    private final FarbStrategyMapper strategyMapper;
    private final FarbSignalCache signalCache;
    private final PairExecutor pairExecutor;
    private final FarbEventLogger eventLogger;

    @Scheduled(fixedDelayString = "PT30S", initialDelayString = "PT2M")
    public void checkAll() {
        List<FarbPosition> openPairs = positionMapper.selectList(
                new LambdaQueryWrapper<FarbPosition>().eq(FarbPosition::getStatus, "OPEN"));
        if (openPairs.isEmpty()) return;

        for (FarbPosition p : openPairs) {
            try {
                FarbStrategy s = strategyMapper.selectById(p.getStrategyId());
                if (s == null) continue;
                if (!isEnabled(s)) continue;

                Double movePct = computeAdverseMovePct(p);
                if (movePct == null) continue;

                double safeThreshold = safeThreshold(s);
                if (movePct >= safeThreshold) {
                    fireLiquidationExit(s, p, movePct, safeThreshold);
                }
            } catch (Exception ex) {
                log.warn("[LiquidationGuard] check pair={} failed: {}", p.getId(), ex.getMessage());
            }
        }
    }

    /** perp 空仓反向 = 价格上涨。返回 mark 相对 entry 的涨幅（正数=对空头不利）。*/
    private Double computeAdverseMovePct(FarbPosition pair) {
        var prem = signalCache.latestPremium(pair.getSymbol());
        if (prem == null || pair.getEntryPerpPrice() == null) return null;
        BigDecimal entry = pair.getEntryPerpPrice();
        if (entry.compareTo(BigDecimal.ZERO) <= 0) return null;
        BigDecimal current = BigDecimal.valueOf(prem.value().markPrice());
        return current.subtract(entry)
                .divide(entry, 6, RoundingMode.HALF_UP)
                .doubleValue();
    }

    private boolean isEnabled(FarbStrategy s) {
        return s.getLiquidationProtectionEnabled() == null
                || s.getLiquidationProtectionEnabled() == 1;
    }

    /** 默认取 0.7 × (1/leverage)，即距真实强平线还剩 30% 缓冲时就出。可被 strategy 覆盖。*/
    private double safeThreshold(FarbStrategy s) {
        if (s.getLiquidationSafeMovePct() != null) {
            return s.getLiquidationSafeMovePct().doubleValue() / 100.0;
        }
        int lev = s.getPerpLeverage() != null ? s.getPerpLeverage() : 2;
        double liqMove = 1.0 / lev;   // 简化真实强平位（不考虑维持保证金）
        return liqMove * 0.7;         // 留 30% 缓冲
    }

    private void fireLiquidationExit(FarbStrategy s, FarbPosition pair,
                                      double movePct, double threshold) {
        log.warn("[LiquidationGuard] FORCE EXIT pair={} sym={} movePct={} >= threshold={}",
                pair.getId(), pair.getSymbol(), movePct, threshold);
        eventLogger.positionError(s.getId(), pair.getId(), pair.getSymbol(),
                "LIQUIDATION_RISK_TRIGGERED",
                Map.of("adverseMovePct", movePct,
                       "threshold", threshold,
                       "leverage", s.getPerpLeverage()));
        pairExecutor.closePair(s, pair, "LIQUIDATION_RISK");
    }
}

package com.vertex.service.farb.execution;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.vertex.service.farb.entity.FarbPosition;
import com.vertex.service.farb.entity.FarbSettlementLog;
import com.vertex.service.farb.mapper.FarbPositionMapper;
import com.vertex.service.farb.mapper.FarbSettlementLogMapper;
import com.vertex.service.farb.service.FarbEventLogger;
import com.vertex.service.farb.signal.FarbSignalCache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

/**
 * 资金费套利 - 结算记账。
 * <p>
 * 每 30s 扫 OPEN pair：如果 pair.nextExpectedSettlementTime ≤ now，从 SignalCache 拿该 symbol
 * 结算时刻的 fundingRate 计算 funding_pnl，写入 farb_settlement_log 并累加到 pair.actualFundingPnl。
 * </p>
 * <p>
 * <b>简化实现（Sprint 5）</b>：不监听 UserDataStream 的 FUNDING_FEE 事件，而是**用真实结算时点的
 * fundingRate 本地计算**。此方案在 PAPER 和 LIVE 都一致，逻辑简单。缺点：LIVE 时若币安实际到账
 * 稍有偏差（税/round），本地记账会略微偏离；但足够 P&L 归因用。
 * </p>
 * <p>
 * <b>后续升级路径</b>：接入 UserDataStreamManager 监听真实 FUNDING_FEE income event，用实际
 * 到账金额替代本地计算。放到 Sprint 6 Paper Mode 之后一起做。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SettlementBooker {

    private final FarbPositionMapper positionMapper;
    private final FarbSettlementLogMapper settlementLogMapper;
    private final FarbSignalCache signalCache;
    private final FarbEventLogger eventLogger;
    private final com.vertex.service.farb.signal.FarbSignalStore signalStore;

    /** 每 30 秒扫一遍 */
    @Scheduled(fixedDelayString = "PT30S", initialDelayString = "PT2M")
    public void bookAll() {
        List<FarbPosition> openPairs = positionMapper.selectList(new LambdaQueryWrapper<FarbPosition>()
                .eq(FarbPosition::getStatus, "OPEN")
                .isNotNull(FarbPosition::getNextExpectedSettlementTime));
        if (openPairs.isEmpty()) return;

        LocalDateTime now = LocalDateTime.now();
        for (FarbPosition pair : openPairs) {
            try {
                if (pair.getNextExpectedSettlementTime().isAfter(now)) continue;
                bookOne(pair);
            } catch (Exception ex) {
                log.warn("[SettlementBooker] book pair={} failed: {}", pair.getId(), ex.getMessage());
            }
        }
    }

    /**
     * 对一个 pair 记账**所有已到达而未记账的结算**（v2：backfill 支持）。
     * 从 pair.lastSettlementTime 到 now 遍历 RocksDB 里的所有 funding 条目，
     * 每条用**该条真实的 rate 和 ts**记账，避免用最新 rate 补历史。
     */
    void bookOne(FarbPosition pair) {
        String symbol = pair.getSymbol();
        LocalDateTime lastSettle = pair.getLastSettlementTime() != null
                ? pair.getLastSettlementTime()
                : pair.getEntryTime();
        if (lastSettle == null) return;
        long fromTs = lastSettle.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        long toTs = System.currentTimeMillis();

        // 从 RocksDB 拉真实 ts + rate（每条独立记账）
        var tsVals = signalStore.scanByRangeWithTs(
                com.vertex.service.farb.signal.FarbSignalStore.PFX_FUNDING,
                symbol, fromTs + 1, toTs,
                com.vertex.service.farb.signal.FarbSignalCache.FundingRecord.class);
        if (tsVals.isEmpty()) return;

        int booked = 0;
        BigDecimal accumFundingAmount = zero(pair.getActualFundingPnl());
        LocalDateTime lastEventTime = null;
        int lastIntervalHours = 8;

        for (var tv : tsVals) {
            double rate = tv.value().rate();
            int intervalHours = tv.value().intervalHours();
            lastIntervalHours = intervalHours;
            LocalDateTime settleAt = LocalDateTime.ofInstant(
                    java.time.Instant.ofEpochMilli(tv.ts()), ZoneId.systemDefault());

            // 幂等：同 (position_id, settlement_time) 已存在则跳过
            Long existCnt = settlementLogMapper.selectCount(
                    new LambdaQueryWrapper<FarbSettlementLog>()
                            .eq(FarbSettlementLog::getPositionId, pair.getId())
                            .eq(FarbSettlementLog::getSettlementTime, settleAt));
            if (existCnt != null && existCnt > 0) {
                lastEventTime = settleAt;   // 仍然推进游标
                continue;
            }

            // U 本位合约：funding = qty × mark(结算时刻) × rate
            // 查该结算时刻的 mark price（premiumAt 返回 ≤ tsCutoff 的最近快照）
            BigDecimal markAtSettle = pair.getEntryPerpPrice();  // 兜底
            var premSnap = signalCache.premiumAt(symbol, tv.ts());
            if (premSnap != null && premSnap.value().markPrice() > 0) {
                markAtSettle = BigDecimal.valueOf(premSnap.value().markPrice());
            }

            BigDecimal fundingAmount = pair.getPerpQty()
                    .multiply(markAtSettle)
                    .multiply(BigDecimal.valueOf(rate))
                    .setScale(8, RoundingMode.HALF_UP);

            FarbSettlementLog logRow = new FarbSettlementLog();
            logRow.setStrategyId(pair.getStrategyId());
            logRow.setPositionId(pair.getId());
            logRow.setSymbol(symbol);
            logRow.setSettlementTime(settleAt);
            logRow.setFundingRate(BigDecimal.valueOf(rate).setScale(10, RoundingMode.HALF_UP));
            logRow.setFundingIntervalHours(intervalHours);
            logRow.setMarkPriceAtSettle(markAtSettle);
            logRow.setPerpQty(pair.getPerpQty());
            logRow.setFundingAmount(fundingAmount);
            logRow.setSource("LOCAL_ESTIMATE");
            settlementLogMapper.insert(logRow);

            accumFundingAmount = accumFundingAmount.add(fundingAmount);
            lastEventTime = settleAt;
            booked++;

            eventLogger.positionInfo(pair.getStrategyId(), pair.getId(), symbol,
                    "SETTLEMENT_RECEIVED", Map.of(
                            "rate", rate,
                            "intervalHours", intervalHours,
                            "markPrice", markAtSettle,
                            "fundingAmount", fundingAmount,
                            "cumulativeFundingPnl", accumFundingAmount,
                            "settlementIndex", booked));
        }

        if (booked > 0 && lastEventTime != null) {
            pair.setActualFundingPnl(accumFundingAmount);
            pair.setLastSettlementTime(lastEventTime);
            pair.setSettlementCount(zeroInt(pair.getSettlementCount()) + booked);
            // 下次预期时间 = 最后一次实际时间 + interval
            pair.setNextExpectedSettlementTime(lastEventTime.plusHours(lastIntervalHours));
            positionMapper.updateById(pair);
        }
    }

    private BigDecimal zero(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }
    private int zeroInt(Integer v) { return v == null ? 0 : v; }
}

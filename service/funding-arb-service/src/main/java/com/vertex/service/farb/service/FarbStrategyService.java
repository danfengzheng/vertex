package com.vertex.service.farb.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.vertex.service.farb.entity.FarbStrategy;
import com.vertex.service.farb.mapper.FarbStrategyMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

/**
 * 资金费套利 - 策略 CRUD + 生命周期管理（Sprint 1 骨架，具体业务在后续 Sprint 补齐）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FarbStrategyService {

    private final FarbStrategyMapper strategyMapper;

    public FarbStrategy get(long id) {
        return strategyMapper.selectById(id);
    }

    public List<FarbStrategy> list() {
        return strategyMapper.selectList(new LambdaQueryWrapper<FarbStrategy>()
                .eq(FarbStrategy::getDeleted, 0)
                .orderByDesc(FarbStrategy::getCreateTime));
    }

    public List<FarbStrategy> listRunning() {
        return strategyMapper.selectList(new LambdaQueryWrapper<FarbStrategy>()
                .eq(FarbStrategy::getStatus, "RUNNING")
                .eq(FarbStrategy::getDeleted, 0));
    }

    public Long create(FarbStrategy s) {
        applyDefaults(s);
        strategyMapper.insert(s);
        log.info("[FarbStrategyService] created strategy id={} name={}", s.getId(), s.getName());
        return s.getId();
    }

    public void update(long id, FarbStrategy patch) {
        FarbStrategy existing = strategyMapper.selectById(id);
        if (existing == null) throw new IllegalArgumentException("strategy not found: " + id);
        // 防御：runtime 快照字段不允许通过 PUT 修改
        clearRuntimeFields(patch);
        patch.setId(id);
        strategyMapper.updateById(patch);
    }

    public void delete(long id) {
        FarbStrategy s = strategyMapper.selectById(id);
        if (s == null) return;
        if ("RUNNING".equals(s.getStatus())) {
            throw new IllegalStateException("cannot delete RUNNING strategy, stop first");
        }
        strategyMapper.deleteById(id);
    }

    /** Sprint 1 stub：真正的启动逻辑在 Sprint 4 PairExecutor 完成后补齐 */
    public void start(long id) {
        FarbStrategy s = strategyMapper.selectById(id);
        if (s == null) throw new IllegalArgumentException("strategy not found: " + id);
        if ("RUNNING".equals(s.getStatus())) {
            log.info("[FarbStrategyService] strategy={} already RUNNING", id);
            return;
        }
        s.setStatus("RUNNING");
        strategyMapper.updateById(s);
        log.info("[FarbStrategyService] strategy={} started (Sprint 1 stub — no scanner yet)", id);
    }

    public void stop(long id) {
        FarbStrategy s = strategyMapper.selectById(id);
        if (s == null) return;
        if ("STOPPED".equals(s.getStatus())) return;
        s.setStatus("STOPPED");
        strategyMapper.updateById(s);
        log.info("[FarbStrategyService] strategy={} stopped", id);
    }

    // ─── 内部 ────────────────────────────────────────────────────────────────

    private void applyDefaults(FarbStrategy s) {
        if (s.getStatus() == null) s.setStatus("STOPPED");
        if (s.getExecutionMode() == null) s.setExecutionMode("PAPER");
        if (s.getScanAllMarket() == null) s.setScanAllMarket(1);
        if (s.getMin24hVolumeUsd() == null) s.setMin24hVolumeUsd(new BigDecimal("10000000"));
        if (s.getLookbackSettlements() == null) s.setLookbackSettlements(6);
        if (s.getMinAvgFundingBps() == null) s.setMinAvgFundingBps(new BigDecimal("4.00"));
        if (s.getMinHitRatio() == null) s.setMinHitRatio(new BigDecimal("0.67"));
        if (s.getMinLatestFundingBps() == null) s.setMinLatestFundingBps(new BigDecimal("2.00"));
        if (s.getMaxEntryBasisBps() == null) s.setMaxEntryBasisBps(new BigDecimal("20.00"));
        if (s.getLsrMaxExtreme() == null) s.setLsrMaxExtreme(new BigDecimal("3.00"));
        if (s.getOiCrowdingThresholdPct() == null) s.setOiCrowdingThresholdPct(new BigDecimal("30.00"));
        if (s.getVolSpikeMultiplier() == null) s.setVolSpikeMultiplier(new BigDecimal("2.00"));
        if (s.getRequireTakerConfirm() == null) s.setRequireTakerConfirm(0);
        if (s.getExitRegimeBreakCount() == null) s.setExitRegimeBreakCount(2);
        if (s.getExitBasisDeltaBps() == null) s.setExitBasisDeltaBps(new BigDecimal("60.00"));
        if (s.getExitTakeProfitPct() == null) s.setExitTakeProfitPct(new BigDecimal("0.80"));
        if (s.getExitMaxHoldHours() == null) s.setExitMaxHoldHours(168);
        if (s.getBasisEmergencyBps() == null) s.setBasisEmergencyBps(new BigDecimal("120.00"));
        if (s.getNotionalPerPair() == null) s.setNotionalPerPair(new BigDecimal("500"));
        if (s.getMaxOpenPairs() == null) s.setMaxOpenPairs(8);
        if (s.getMaxPairsPerSymbol() == null) s.setMaxPairsPerSymbol(1);
        if (s.getPerpLeverage() == null) s.setPerpLeverage(2);
        if (s.getPerpMarginType() == null) s.setPerpMarginType("ISOLATED");
        if (s.getUseRealtimeFee() == null) s.setUseRealtimeFee(1);
        if (s.getUseBnbDiscount() == null) s.setUseBnbDiscount(1);
        if (s.getSpotFeeRate() == null) s.setSpotFeeRate(new BigDecimal("0.00075"));
        if (s.getPerpFeeRate() == null) s.setPerpFeeRate(new BigDecimal("0.00036"));
        if (s.getMinExpectedYieldPct() == null) s.setMinExpectedYieldPct(new BigDecimal("8.00"));
        if (s.getMaxDrawdownPct() == null) s.setMaxDrawdownPct(new BigDecimal("15.00"));
        if (s.getFundingReversalStopDays() == null) s.setFundingReversalStopDays(3);
    }

    private void clearRuntimeFields(FarbStrategy patch) {
        patch.setStatus(null);
        patch.setCurrentOpenPairs(null);
        patch.setTotalFundingPnl(null);
        patch.setTotalBasisPnl(null);
        patch.setTotalFeePaid(null);
        patch.setTotalNetPnl(null);
        patch.setTotalPairsOpened(null);
        patch.setTotalPairsClosed(null);
    }
}

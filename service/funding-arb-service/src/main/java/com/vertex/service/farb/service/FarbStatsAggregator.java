package com.vertex.service.farb.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.vertex.service.farb.entity.FarbPosition;
import com.vertex.service.farb.entity.FarbStrategy;
import com.vertex.service.farb.mapper.FarbPositionMapper;
import com.vertex.service.farb.mapper.FarbStrategyMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * 资金费套利 - 策略级 rollup 聚合器。
 * <p>
 * 每 30s 聚合每个策略的仓位统计写回 farb_strategy 字段。
 * 之前这些字段（currentOpenPairs/totalFundingPnl/…）从来没有被写入，
 * 导致主列表和详情页顶部一律显示 0。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FarbStatsAggregator {

    private final FarbStrategyMapper strategyMapper;
    private final FarbPositionMapper positionMapper;

    @Scheduled(fixedDelayString = "PT30S", initialDelayString = "PT90S")
    public void aggregateAll() {
        List<FarbStrategy> strategies = strategyMapper.selectList(null);
        for (FarbStrategy s : strategies) {
            try {
                aggregateOne(s);
            } catch (Exception ex) {
                log.warn("[StatsAggregator] agg strategy={} failed: {}", s.getId(), ex.getMessage());
            }
        }
    }

    void aggregateOne(FarbStrategy strategy) {
        List<FarbPosition> pairs = positionMapper.selectList(new LambdaQueryWrapper<FarbPosition>()
                .eq(FarbPosition::getStrategyId, strategy.getId()));
        if (pairs.isEmpty()) return;

        int openCount = 0, closedCount = 0;
        BigDecimal totalFunding = BigDecimal.ZERO;
        BigDecimal totalBasis = BigDecimal.ZERO;
        BigDecimal totalFee = BigDecimal.ZERO;

        for (FarbPosition p : pairs) {
            String status = p.getStatus();
            if ("OPEN".equals(status)) {
                openCount++;
                // OPEN：只累加未实现 funding + 已支付入场手续费
                totalFunding = totalFunding.add(nz(p.getActualFundingPnl()));
                totalFee = totalFee.add(nz(p.getEntrySpotFee())).add(nz(p.getEntryPerpFee()));
            } else if ("CLOSED".equals(status)) {
                closedCount++;
                // CLOSED：使用归因字段
                totalFunding = totalFunding.add(nz(p.getFundingPnl()));
                totalBasis = totalBasis.add(nz(p.getBasisPnl()));
                totalFee = totalFee.add(nz(p.getFeePaid()));
            }
            // ERROR / ABORTED 等中间态不计入
        }
        BigDecimal totalNet = totalFunding.add(totalBasis).subtract(totalFee);

        // 只更新变化字段，避免版本冲突
        FarbStrategy patch = new FarbStrategy();
        patch.setId(strategy.getId());
        patch.setCurrentOpenPairs(openCount);
        patch.setTotalFundingPnl(totalFunding);
        patch.setTotalBasisPnl(totalBasis);
        patch.setTotalFeePaid(totalFee);
        patch.setTotalNetPnl(totalNet);
        patch.setTotalPairsOpened(pairs.size());
        patch.setTotalPairsClosed(closedCount);
        strategyMapper.updateById(patch);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}

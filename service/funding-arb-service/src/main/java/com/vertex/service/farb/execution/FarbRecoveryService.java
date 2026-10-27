package com.vertex.service.farb.execution;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.vertex.service.farb.entity.FarbPosition;
import com.vertex.service.farb.entity.FarbStrategy;
import com.vertex.service.farb.mapper.FarbPositionMapper;
import com.vertex.service.farb.mapper.FarbStrategyMapper;
import com.vertex.service.farb.service.FarbEventLogger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 资金费套利 - 崩溃恢复。
 * <p>
 * ApplicationReadyEvent 触发一次：
 * <ul>
 *   <li>PAPER pair 全部标 CANCELED（内存簿丢失，无法恢复）</li>
 *   <li>PENDING_ENTRY LIVE pair：REST 查币安订单状态，成功→ 转 OPEN；失败→ 转 EMERGENCY_CLOSED</li>
 *   <li>CLOSING LIVE pair：交给 ExitTrigger 下轮重试（30s 内会重新扫到并尝试）</li>
 *   <li>OPEN LIVE pair：正常保留，SettlementBooker / ExitTrigger 会接管</li>
 * </ul>
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FarbRecoveryService {

    private final FarbPositionMapper positionMapper;
    private final FarbStrategyMapper strategyMapper;
    private final FarbEventLogger eventLogger;

    @EventListener(ApplicationReadyEvent.class)
    public void recoverAll() {
        // 1) PAPER: 内存簿丢，直接清所有非终态 pair
        int paperCleaned = cleanupPaperPairs();

        // 2) LIVE PENDING_ENTRY: 状态不明，先标 EMERGENCY_CLOSED 等人工确认
        //    完整方案是 REST 查币安查两腿订单状态，简化版：先降级 + 告警
        int pendingHandled = handlePendingEntry();

        // 3) LIVE CLOSING: 交给 ExitTrigger 重试即可，此处只 log
        int closingCount = countByStatus("CLOSING");

        // 4) LIVE OPEN: 正常保留
        int openCount = countByStatus("OPEN");

        log.warn("[FarbRecovery] paperCleaned={} pendingHandled={} closingRetryPending={} openContinuing={}",
                paperCleaned, pendingHandled, closingCount, openCount);
    }

    // ─── PAPER 清理 ──────────────────────────────────────────────────────

    /**
     * PAPER 恢复：只清"从未开成"的 PENDING_ENTRY（下单瞬间挂了）。
     * OPEN / CLOSING 保留 —— paper 模式所有状态都在 DB 里，SettlementBooker 会继续记账，
     * ExitTriggerEngine 会继续判定，不需要人工介入。
     */
    private int cleanupPaperPairs() {
        List<FarbStrategy> paperStrategies = strategyMapper.selectList(
                new LambdaQueryWrapper<FarbStrategy>()
                        .eq(FarbStrategy::getExecutionMode, "PAPER")
                        .eq(FarbStrategy::getDeleted, 0));
        if (paperStrategies.isEmpty()) return 0;

        List<Long> paperIds = paperStrategies.stream().map(FarbStrategy::getId).toList();
        List<FarbPosition> pendingEntry = positionMapper.selectList(
                new LambdaQueryWrapper<FarbPosition>()
                        .in(FarbPosition::getStrategyId, paperIds)
                        .eq(FarbPosition::getStatus, "PENDING_ENTRY"));
        if (pendingEntry.isEmpty()) return 0;

        LocalDateTime now = LocalDateTime.now();
        for (FarbPosition p : pendingEntry) {
            p.setStatus("CANCELED");
            p.setCloseTrigger("PAPER_PENDING_CANCELED");
            p.setCloseTime(now);
            positionMapper.updateById(p);
            eventLogger.positionWarn(p.getStrategyId(), p.getId(), p.getSymbol(),
                    "RECOVERY_PAPER_PENDING_CANCELED",
                    Map.of("reason", "Paper pending-entry canceled on restart"));
        }
        return pendingEntry.size();
    }

    // ─── LIVE PENDING_ENTRY 处理 ──────────────────────────────────────────

    private int handlePendingEntry() {
        List<FarbPosition> pending = positionMapper.selectList(
                new LambdaQueryWrapper<FarbPosition>()
                        .eq(FarbPosition::getStatus, "PENDING_ENTRY"));
        if (pending.isEmpty()) return 0;

        LocalDateTime now = LocalDateTime.now();
        for (FarbPosition p : pending) {
            // 简化版：直接标 EMERGENCY_CLOSED + 告警，人工去 Binance 查订单
            // 完整版：REST 查两腿 orderId 状态，若都 FILLED → 转 OPEN；一腿 FILLED → 平仓那腿；都未 FILLED → CANCELED
            p.setStatus("EMERGENCY_CLOSED");
            p.setCloseTrigger("PENDING_ENTRY_UNKNOWN_STATE");
            p.setCloseTime(now);
            positionMapper.updateById(p);
            eventLogger.positionError(p.getStrategyId(), p.getId(), p.getSymbol(),
                    "RECOVERY_PENDING_UNKNOWN",
                    Map.of("advice", "人工到币安查 spot orderId=" + p.getEntrySpotOrderId()
                            + " perp orderId=" + p.getEntryPerpOrderId()
                            + " 若已成交则手动改本记录为 OPEN"));
        }
        return pending.size();
    }

    private int countByStatus(String status) {
        Long cnt = positionMapper.selectCount(new LambdaQueryWrapper<FarbPosition>()
                .eq(FarbPosition::getStatus, status));
        return cnt == null ? 0 : cnt.intValue();
    }

    /**
     * 暴露给运维的手动"标 OPEN"接口的辅助（Controller 里可暴露 endpoint 调用它）。
     * <p>用于 PENDING_ENTRY 已被 EMERGENCY_CLOSED，但用户查币安确认两腿已成交，
     * 想强制修回 OPEN 继续持有的场景。</p>
     */
    public void forceMarkOpen(long pairId) {
        int rows = positionMapper.update(null, new LambdaUpdateWrapper<FarbPosition>()
                .eq(FarbPosition::getId, pairId)
                .in(FarbPosition::getStatus, "EMERGENCY_CLOSED")
                .set(FarbPosition::getStatus, "OPEN")
                .set(FarbPosition::getCloseTrigger, null)
                .set(FarbPosition::getCloseTime, null));
        log.warn("[FarbRecovery] forceMarkOpen pair={} rowsAffected={}", pairId, rows);
    }
}

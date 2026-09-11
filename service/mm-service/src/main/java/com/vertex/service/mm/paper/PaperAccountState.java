package com.vertex.service.mm.paper;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Paper 模式的模拟账户状态 —— 记录余额 + 持仓 + margin。
 * <p>
 * 简化实现：不考虑保证金细节，只做持仓记录 + 未实现盈亏计算。
 * </p>
 */
@Slf4j
@Component
public class PaperAccountState {

    private final ConcurrentMap<Long, AccountInfo> accounts = new ConcurrentHashMap<>();

    public AccountInfo getOrCreate(long accountId, BigDecimal initialBalance) {
        return accounts.computeIfAbsent(accountId, k -> new AccountInfo(initialBalance));
    }

    public AccountInfo get(long accountId) {
        return accounts.get(accountId);
    }

    /**
     * 记录一笔 fill 到某仓位（LONG/SHORT）。
     * @param delta 正 = 增仓；负 = 减仓
     */
    public synchronized void recordFill(long accountId, String symbol, String positionSide,
                                        BigDecimal delta, BigDecimal fillPrice) {
        AccountInfo acc = accounts.computeIfAbsent(accountId, k -> new AccountInfo(BigDecimal.ZERO));
        String key = symbol + ":" + positionSide;
        PositionInfo pos = acc.positions.computeIfAbsent(key, k -> new PositionInfo());
        BigDecimal newQty = pos.qty.add(delta);
        if (newQty.compareTo(BigDecimal.ZERO) <= 0) {
            // 全平
            pos.qty = BigDecimal.ZERO;
            pos.avgEntryPrice = BigDecimal.ZERO;
        } else if (delta.compareTo(BigDecimal.ZERO) > 0) {
            // 加仓：加权平均
            BigDecimal totalCost = pos.avgEntryPrice.multiply(pos.qty).add(fillPrice.multiply(delta));
            pos.avgEntryPrice = totalCost.divide(newQty, 8, java.math.RoundingMode.HALF_UP);
            pos.qty = newQty;
        } else {
            // 减仓：保持成本价，只减 qty
            pos.qty = newQty;
        }
    }

    public static class AccountInfo {
        public BigDecimal walletBalance;
        public final ConcurrentMap<String, PositionInfo> positions = new ConcurrentHashMap<>();

        AccountInfo(BigDecimal init) { this.walletBalance = init; }
    }

    public static class PositionInfo {
        public BigDecimal qty = BigDecimal.ZERO;
        public BigDecimal avgEntryPrice = BigDecimal.ZERO;
    }
}

package com.vertex.service.mm.userstream.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * 币安私有流 ACCOUNT_UPDATE 事件（余额 / 持仓变化）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AccountUpdateEvent {

    private long eventTimeMs;
    private long transactionTimeMs;
    /** DEPOSIT / WITHDRAW / ORDER / FUNDING_FEE / ADJUSTMENT ... */
    private String reason;

    private List<BalanceDelta> balances;
    private List<PositionDelta> positions;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BalanceDelta {
        private String asset;
        private BigDecimal walletBalance;
        private BigDecimal crossWalletBalance;
        private BigDecimal balanceChange;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PositionDelta {
        private String symbol;
        private BigDecimal positionAmt;
        private BigDecimal entryPrice;
        private BigDecimal accumulatedRealized;
        private BigDecimal unrealizedPnl;
        private String marginType;
        private BigDecimal isolatedWallet;
        /** LONG / SHORT / BOTH */
        private String positionSide;
    }
}

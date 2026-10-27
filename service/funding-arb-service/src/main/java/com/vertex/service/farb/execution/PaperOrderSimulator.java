package com.vertex.service.farb.execution;

import com.vertex.service.farb.signal.FarbSignalCache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 资金费套利 - PAPER 模式极简模拟器。
 * <p>
 * 与 mm-service 的完整 FillSimulator 不同：funding arb 只用 MARKET 单，
 * 直接从 SignalCache 拿最新 mark/index price 立即"成交"，加 5-15 bps 滑点。
 * </p>
 * <p>
 * 设计要点：
 * <ul>
 *   <li>PAPER 走此模拟器，LIVE 走 SpotPerpOrderClient</li>
 *   <li>成功率固定 100%（PAPER 不模拟拒单，便于用户学习）</li>
 *   <li>滑点模拟：随机 5-15 bps，符合真实市场</li>
 *   <li>clientOrderId 用递增序列，方便测试观察</li>
 * </ul>
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaperOrderSimulator {

    /** PAPER 滑点范围（bps）*/
    private static final int SLIPPAGE_MIN_BPS = 5;
    private static final int SLIPPAGE_MAX_BPS = 15;

    private final FarbSignalCache signalCache;
    private final AtomicLong orderIdSeq = new AtomicLong(System.currentTimeMillis());

    // ─── 现货 ────────────────────────────────────────────────────────────

    public PairOrderResult spotBuy(String symbol, BigDecimal qty, BigDecimal feeRate) {
        BigDecimal indexPrice = getIndexPrice(symbol);
        if (indexPrice == null) {
            return fail("PAPER: no price data for " + symbol);
        }
        // BUY 滑点：成交价 = index × (1 + slippage_bps/10000)
        BigDecimal fillPrice = indexPrice.multiply(
                BigDecimal.ONE.add(BigDecimal.valueOf(randomSlippageBps() / 10000.0)));
        return success("SPOT_BUY", symbol, qty, fillPrice, feeRate);
    }

    public PairOrderResult spotSell(String symbol, BigDecimal qty, BigDecimal feeRate) {
        BigDecimal indexPrice = getIndexPrice(symbol);
        if (indexPrice == null) return fail("PAPER: no price data for " + symbol);
        // SELL 滑点：成交价 = index × (1 - slippage_bps/10000)
        BigDecimal fillPrice = indexPrice.multiply(
                BigDecimal.ONE.subtract(BigDecimal.valueOf(randomSlippageBps() / 10000.0)));
        return success("SPOT_SELL", symbol, qty, fillPrice, feeRate);
    }

    // ─── 期货 ────────────────────────────────────────────────────────────

    public PairOrderResult perpSell(String symbol, BigDecimal qty, BigDecimal feeRate) {
        BigDecimal markPrice = getMarkPrice(symbol);
        if (markPrice == null) return fail("PAPER: no mark price for " + symbol);
        BigDecimal fillPrice = markPrice.multiply(
                BigDecimal.ONE.subtract(BigDecimal.valueOf(randomSlippageBps() / 10000.0)));
        return success("PERP_SELL", symbol, qty, fillPrice, feeRate);
    }

    public PairOrderResult perpBuyReduceOnly(String symbol, BigDecimal qty, BigDecimal feeRate) {
        BigDecimal markPrice = getMarkPrice(symbol);
        if (markPrice == null) return fail("PAPER: no mark price for " + symbol);
        BigDecimal fillPrice = markPrice.multiply(
                BigDecimal.ONE.add(BigDecimal.valueOf(randomSlippageBps() / 10000.0)));
        return success("PERP_BUY", symbol, qty, fillPrice, feeRate);
    }

    // ─── 内部 ────────────────────────────────────────────────────────────

    private BigDecimal getIndexPrice(String symbol) {
        var prem = signalCache.latestPremium(symbol);
        if (prem == null || prem.value().indexPrice() <= 0) return null;
        return BigDecimal.valueOf(prem.value().indexPrice()).setScale(8, RoundingMode.HALF_UP);
    }

    private BigDecimal getMarkPrice(String symbol) {
        var prem = signalCache.latestPremium(symbol);
        if (prem == null || prem.value().markPrice() <= 0) return null;
        return BigDecimal.valueOf(prem.value().markPrice()).setScale(8, RoundingMode.HALF_UP);
    }

    private double randomSlippageBps() {
        return ThreadLocalRandom.current().nextInt(SLIPPAGE_MIN_BPS, SLIPPAGE_MAX_BPS + 1);
    }

    private PairOrderResult success(String side, String symbol, BigDecimal qty,
                                     BigDecimal fillPrice, BigDecimal feeRate) {
        long id = orderIdSeq.incrementAndGet();
        BigDecimal notional = qty.multiply(fillPrice);
        BigDecimal fee = feeRate != null ? notional.multiply(feeRate) : BigDecimal.ZERO;
        log.debug("[PaperSim] {} {} qty={} fillPrice={} fee={}", side, symbol, qty, fillPrice, fee);
        return PairOrderResult.builder()
                .success(true)
                .orderId("PAPER-" + id)
                .clientOrderId("PAPER-CLI-" + id)
                .executedQty(qty)
                .avgFillPrice(fillPrice)
                .feePaid(fee)
                .build();
    }

    private PairOrderResult fail(String reason) {
        log.warn("[PaperSim] fail: {}", reason);
        return PairOrderResult.builder()
                .success(false)
                .errorMessage(reason)
                .build();
    }
}

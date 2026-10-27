package com.vertex.service.farb.execution;

import com.alibaba.fastjson2.JSONObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 双腿 post-only + 先成即市价对方腿。
 * <p>
 * 流程（入场 / 常规出场）：
 * <ol>
 *   <li>取 spot 和 perp 的 bestBid/bestAsk</li>
 *   <li>并发挂 spot LIMIT_MAKER + perp LIMIT_GTX</li>
 *   <li>定期轮询两腿状态</li>
 *   <li>哪腿先 FILLED，立即：
 *     <ul>
 *       <li>compareAndSet 抢"胜者"标记</li>
 *       <li>撤对方腿</li>
 *       <li>如果撤失败/对方也 filled → 两腿都 maker（bonus）</li>
 *       <li>否则对方腿 MARKET 追单</li>
 *     </ul>
 *   </li>
 *   <li>Timeout 都未成 → 撤两腿 → 两腿都 MARKET（降级）</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DualMakerExecutor {

    private final SpotPerpOrderClient orderClient;
    // 每次 executeEntry/Exit 用 4 个 slot（2 book fetch + 2 place order）
    // 支持最多约 8 个 pair 并发 dual-maker，超出会排队但不阻塞主流程
    private final ExecutorService pool = Executors.newFixedThreadPool(32, r -> {
        Thread t = new Thread(r, "farb-dualmaker");
        t.setDaemon(true);
        return t;
    });
    private volatile boolean shuttingDown = false;

    /**
     * Graceful shutdown：先设标记阻止新单，然后等在途 poll 完成（撤单收尾），最后关线程池。
     * 顺序上应先于 SpotPerpOrderClient 关闭（否则 poll/cancel 会异常）。
     */
    @jakarta.annotation.PreDestroy
    public void shutdown() {
        shuttingDown = true;
        log.info("[DualMaker] shutdown: waiting for in-flight polls to finish ...");
        pool.shutdown();
        try {
            if (!pool.awaitTermination(15, java.util.concurrent.TimeUnit.SECONDS)) {
                log.warn("[DualMaker] pool did not terminate in 15s, forcing");
                pool.shutdownNow();
                pool.awaitTermination(3, java.util.concurrent.TimeUnit.SECONDS);
            }
        } catch (InterruptedException ie) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("[DualMaker] shutdown complete");
    }

    /**
     * 入场：spot BUY + perp SELL 开空。入场 qty spot == perp（都是新开）。
     */
    public DualMakerOutcome executeEntry(FarbCredentialResolver.Credentials spotCreds,
                                          FarbCredentialResolver.Credentials perpCreds,
                                          String symbol, BigDecimal qty,
                                          BigDecimal spotMakerFee, BigDecimal perpMakerFee,
                                          BigDecimal spotTakerFee, BigDecimal perpTakerFee,
                                          long timeoutMs, long pollMs) {
        return run(spotCreds, perpCreds, symbol, qty, qty, true,
                spotMakerFee, perpMakerFee, spotTakerFee, perpTakerFee, timeoutMs, pollMs);
    }

    /**
     * 出场：spot SELL + perp BUY reduceOnly。出场 spotQty/perpQty 可能因 stepSize 对齐而略有差异。
     */
    public DualMakerOutcome executeExit(FarbCredentialResolver.Credentials spotCreds,
                                         FarbCredentialResolver.Credentials perpCreds,
                                         String symbol, BigDecimal spotQty, BigDecimal perpQty,
                                         BigDecimal spotMakerFee, BigDecimal perpMakerFee,
                                         BigDecimal spotTakerFee, BigDecimal perpTakerFee,
                                         long timeoutMs, long pollMs) {
        return run(spotCreds, perpCreds, symbol, spotQty, perpQty, false,
                spotMakerFee, perpMakerFee, spotTakerFee, perpTakerFee, timeoutMs, pollMs);
    }

    private DualMakerOutcome run(FarbCredentialResolver.Credentials spotCreds,
                                  FarbCredentialResolver.Credentials perpCreds,
                                  String symbol, BigDecimal spotQty, BigDecimal perpQty, boolean isEntry,
                                  BigDecimal spotMakerFee, BigDecimal perpMakerFee,
                                  BigDecimal spotTakerFee, BigDecimal perpTakerFee,
                                  long timeoutMs, long pollMs) {
        // 0a) shutdown 中拒绝新单，让 PairExecutor 走 taker fallback（更快完成）
        if (shuttingDown) {
            return DualMakerOutcome.needsFallback("dualmaker shutting down");
        }
        // 0b) qty null / 负数保护（必须先做，否则后续 spotQty.min NPE）
        if (spotQty == null || perpQty == null
                || spotQty.signum() <= 0 || perpQty.signum() <= 0) {
            return DualMakerOutcome.failed("qty null or non-positive");
        }
        // 参考 qty（用于 balance tolerance / 追单基准）取两者最小值，保守
        BigDecimal qty = spotQty.min(perpQty);
        // 1) 取 book ticker（并发）
        CompletableFuture<BigDecimal[]> spotBookFut = CompletableFuture.supplyAsync(
                () -> orderClient.getSpotBookTicker(symbol), pool);
        CompletableFuture<BigDecimal[]> perpBookFut = CompletableFuture.supplyAsync(
                () -> orderClient.getPerpBookTicker(symbol), pool);
        BigDecimal[] spotBook, perpBook;
        try {
            spotBook = spotBookFut.get();
            perpBook = perpBookFut.get();
        } catch (Exception ex) {
            return DualMakerOutcome.failed("bookTicker exception: " + ex.getMessage());
        }
        if (spotBook[0] == null || spotBook[1] == null || perpBook[0] == null || perpBook[1] == null) {
            return DualMakerOutcome.failed("book ticker null");
        }
        // 价格 sanity 校验：任何腿 bid/ask <= 0 或 bid > ask → 数据异常
        if (spotBook[0].signum() <= 0 || spotBook[1].signum() <= 0
                || perpBook[0].signum() <= 0 || perpBook[1].signum() <= 0
                || spotBook[0].compareTo(spotBook[1]) > 0
                || perpBook[0].compareTo(perpBook[1]) > 0) {
            log.warn("[DualMaker] bad book ticker sym={} spot=[{},{}] perp=[{},{}]",
                    symbol, spotBook[0], spotBook[1], perpBook[0], perpBook[1]);
            return DualMakerOutcome.failed("book ticker prices invalid");
        }

        // 2) 挂单价：入场 spot BUY @bestBid（保守）/ perp SELL @bestAsk
        //           出场 spot SELL @bestAsk / perp BUY @bestBid
        BigDecimal spotPrice = isEntry ? spotBook[0] : spotBook[1];
        BigDecimal perpPrice = isEntry ? perpBook[1] : perpBook[0];

        // 3) 并发挂两腿 post-only（spot 用 spotQty，perp 用 perpQty）
        CompletableFuture<PairOrderResult> spotFut = CompletableFuture.supplyAsync(() ->
                isEntry
                    ? orderClient.spotPostOnlyBuy(spotCreds.apiKey(), spotCreds.apiSecret(),
                            symbol, spotQty, spotPrice, spotMakerFee)
                    : orderClient.spotPostOnlySell(spotCreds.apiKey(), spotCreds.apiSecret(),
                            symbol, spotQty, spotPrice, spotMakerFee), pool);
        CompletableFuture<PairOrderResult> perpFut = CompletableFuture.supplyAsync(() ->
                isEntry
                    ? orderClient.perpPostOnlySell(perpCreds.apiKey(), perpCreds.apiSecret(),
                            symbol, perpQty, perpPrice, perpMakerFee)
                    : orderClient.perpPostOnlyBuyReduceOnly(perpCreds.apiKey(), perpCreds.apiSecret(),
                            symbol, perpQty, perpPrice, perpMakerFee), pool);
        PairOrderResult spotPlaced, perpPlaced;
        try {
            spotPlaced = spotFut.get();
            perpPlaced = perpFut.get();
        } catch (Exception ex) {
            return DualMakerOutcome.failed("place exception: " + ex.getMessage());
        }
        // 挂单本身失败（例如 LIMIT_MAKER 被拒因价格穿越）→ 立即降级
        if (!spotPlaced.isSuccess() && !perpPlaced.isSuccess()) {
            return DualMakerOutcome.needsFallback("both post-only rejected");
        }

        // 一腿挂单失败的日志（另一腿会退化成单腿 maker 路径）
        if (!spotPlaced.isSuccess()) {
            log.info("[DualMaker] spot post-only rejected sym={} err={} → single-leg maker path",
                    symbol, spotPlaced.getErrorMessage());
        }
        if (!perpPlaced.isSuccess()) {
            log.info("[DualMaker] perp post-only rejected sym={} err={} → single-leg maker path",
                    symbol, perpPlaced.getErrorMessage());
        }

        // 4) 轮询等 fill（接受 FILLED 和 PARTIALLY_FILLED；partial 视同 winner，追单只补差额）
        long deadline = System.currentTimeMillis() + timeoutMs;
        AtomicReference<String> winner = new AtomicReference<>(null);
        PairOrderResult spotFinal = null, perpFinal = null;

        while (System.currentTimeMillis() < deadline) {
            try { Thread.sleep(pollMs); } catch (InterruptedException ie) {
                Thread.currentThread().interrupt(); break;
            }

            // 查 spot
            if (spotFinal == null && spotPlaced.isSuccess() && spotPlaced.getOrderId() != null) {
                JSONObject q = orderClient.spotQueryOrder(spotCreds.apiKey(), spotCreds.apiSecret(),
                        symbol, spotPlaced.getOrderId());
                if (isFillableStatus(q)) {
                    spotFinal = buildFilledResult(q, spotMakerFee, false);
                    if (winner.compareAndSet(null, "SPOT")) {
                        // 撤 perp
                        if (perpPlaced.isSuccess() && perpPlaced.getOrderId() != null) {
                            orderClient.perpCancel(perpCreds.apiKey(), perpCreds.apiSecret(),
                                    symbol, perpPlaced.getOrderId());
                        }
                        // 如果 spot 是 PARTIALLY_FILLED，立即撤自己剩余部分（防止继续被吃到超量）
                        if ("PARTIALLY_FILLED".equals(q.getString("status"))) {
                            orderClient.spotCancel(spotCreds.apiKey(), spotCreds.apiSecret(),
                                    symbol, spotPlaced.getOrderId());
                            // 撤后再查一次拿最新 executedQty
                            // 注意：cancel 后 status 可能变 CANCELED，此时也可能有 executedQty > 0
                            //      （撤单窗口内被吃了几笔）—— 必须用最新值，否则 delta 漂移
                            JSONObject q2 = orderClient.spotQueryOrder(spotCreds.apiKey(), spotCreds.apiSecret(),
                                    symbol, spotPlaced.getOrderId());
                            spotFinal = updatedIfLarger(q2, spotFinal, spotMakerFee, false);
                        }
                    }
                }
            }

            // 查 perp
            if (perpFinal == null && perpPlaced.isSuccess() && perpPlaced.getOrderId() != null) {
                JSONObject q = orderClient.perpQueryOrder(perpCreds.apiKey(), perpCreds.apiSecret(),
                        symbol, perpPlaced.getOrderId());
                if (isFillableStatus(q)) {
                    perpFinal = buildFilledResult(q, perpMakerFee, true);
                    if (winner.compareAndSet(null, "PERP")) {
                        if (spotPlaced.isSuccess() && spotPlaced.getOrderId() != null) {
                            orderClient.spotCancel(spotCreds.apiKey(), spotCreds.apiSecret(),
                                    symbol, spotPlaced.getOrderId());
                        }
                        // 如果 perp 是 PARTIALLY_FILLED，撤自己剩余部分
                        if ("PARTIALLY_FILLED".equals(q.getString("status"))) {
                            orderClient.perpCancel(perpCreds.apiKey(), perpCreds.apiSecret(),
                                    symbol, perpPlaced.getOrderId());
                            JSONObject q2 = orderClient.perpQueryOrder(perpCreds.apiKey(), perpCreds.apiSecret(),
                                    symbol, perpPlaced.getOrderId());
                            perpFinal = updatedIfLarger(q2, perpFinal, perpMakerFee, true);
                        }
                    }
                }
            }

            if (spotFinal != null && perpFinal != null) {
                if (!isEntry) {
                    // 出场：两腿都有成交，但可能都是 partial —— 必须把剩余持仓平掉
                    return exitCloseRemainders(spotFinal, perpFinal, symbol, spotQty, perpQty,
                            spotCreds, perpCreds, spotTakerFee, perpTakerFee);
                }
                // 入场：balanceLegs 前先 re-query 两腿，拿真正最终的 executedQty
                // （loser 腿在 cancel 生效前的 window 内可能又被吃了几笔，poll 值 stale）
                spotFinal = reQueryFinal(true, spotCreds, symbol, spotPlaced, spotMakerFee, spotFinal);
                perpFinal = reQueryFinal(false, perpCreds, symbol, perpPlaced, perpMakerFee, perpFinal);

                BigDecimal diff = spotFinal.getExecutedQty().subtract(perpFinal.getExecutedQty()).abs();
                BigDecimal tolerance = qty.multiply(new BigDecimal("0.001"));   // 0.1%
                if (diff.compareTo(tolerance) <= 0) {
                    log.info("[DualMaker] entry both maker sym={} spotQty={} perpQty={}",
                            symbol, spotFinal.getExecutedQty(), perpFinal.getExecutedQty());
                    return DualMakerOutcome.success(spotFinal, perpFinal, "BOTH_MAKER");
                }
                return balanceLegs(spotFinal, perpFinal, symbol, isEntry,
                        spotCreds, perpCreds, spotTakerFee, perpTakerFee);
            }

            // 一腿成（含 partial）+ 另一腿未成 → 追市价补齐
            //   入场：chase = winner 实际成交量（保持两腿 delta-neutral）
            //   出场：chase = pair 原始 qty - 已成交量（把剩余持仓全部平掉），两腿独立
            if (winner.get() != null) {
                if ("SPOT".equals(winner.get()) && perpFinal == null) {
                    BigDecimal chaseQty = isEntry
                            ? spotFinal.getExecutedQty()             // 入场：跟 spot 成交量
                            : perpQty;                                // 出场：把 pair 里全部 perp 平掉
                    PairOrderResult perpMarket = isEntry
                            ? orderClient.perpSellMarket(perpCreds.apiKey(), perpCreds.apiSecret(),
                                    symbol, chaseQty, perpTakerFee)
                            : orderClient.perpBuyReduceOnly(perpCreds.apiKey(), perpCreds.apiSecret(),
                                    symbol, chaseQty, perpTakerFee);
                    if (!perpMarket.isSuccess()) {
                        return DualMakerOutcome.partialLegRisk(spotFinal, "SPOT_MAKER_PERP_MKT_FAIL");
                    }
                    // 出场时如果 spot 是 partial（executedQty < spotQty），还需要 market 平剩余 spot
                    if (!isEntry) {
                        BigDecimal spotRemaining = spotQty.subtract(spotFinal.getExecutedQty());
                        if (spotRemaining.signum() > 0) {
                            PairOrderResult spotExtra = orderClient.spotSellMarket(
                                    spotCreds.apiKey(), spotCreds.apiSecret(),
                                    symbol, spotRemaining, spotTakerFee);
                            if (!spotExtra.isSuccess()) {
                                // spot 部分留在盘中 → 必须人工
                                return DualMakerOutcome.partialLegRisk(spotFinal,
                                        "EXIT_SPOT_REMAINDER_MKT_FAIL");
                            }
                            spotFinal = mergeFill(spotFinal, spotExtra);
                        }
                    }
                    log.info("[DualMaker] SPOT_MAKER sym={} isEntry={} chaseQty={}",
                            symbol, isEntry, chaseQty);
                    return DualMakerOutcome.success(spotFinal, perpMarket, "SPOT_MAKER");
                }
                if ("PERP".equals(winner.get()) && spotFinal == null) {
                    BigDecimal chaseQty = isEntry
                            ? perpFinal.getExecutedQty()
                            : spotQty;
                    PairOrderResult spotMarket = isEntry
                            ? orderClient.spotBuyMarket(spotCreds.apiKey(), spotCreds.apiSecret(),
                                    symbol, chaseQty, spotTakerFee)
                            : orderClient.spotSellMarket(spotCreds.apiKey(), spotCreds.apiSecret(),
                                    symbol, chaseQty, spotTakerFee);
                    if (!spotMarket.isSuccess()) {
                        return DualMakerOutcome.partialLegRisk(perpFinal, "PERP_MAKER_SPOT_MKT_FAIL");
                    }
                    if (!isEntry) {
                        BigDecimal perpRemaining = perpQty.subtract(perpFinal.getExecutedQty());
                        if (perpRemaining.signum() > 0) {
                            PairOrderResult perpExtra = orderClient.perpBuyReduceOnly(
                                    perpCreds.apiKey(), perpCreds.apiSecret(),
                                    symbol, perpRemaining, perpTakerFee);
                            if (!perpExtra.isSuccess()) {
                                return DualMakerOutcome.partialLegRisk(perpFinal,
                                        "EXIT_PERP_REMAINDER_MKT_FAIL");
                            }
                            perpFinal = mergeFill(perpFinal, perpExtra);
                        }
                    }
                    log.info("[DualMaker] PERP_MAKER sym={} isEntry={} chaseQty={}",
                            symbol, isEntry, chaseQty);
                    return DualMakerOutcome.success(spotMarket, perpFinal, "PERP_MAKER");
                }
            }
        }

        // 5) 超时都未 fill → 撤两腿，撤前再查一次确认状态（防止撤单竞态：正在撤时被 filled）
        PairOrderResult spotAfterCancel = safeCancelAndConfirm(true, spotCreds, symbol, spotPlaced, spotMakerFee);
        PairOrderResult perpAfterCancel = safeCancelAndConfirm(false, perpCreds, symbol, perpPlaced, perpMakerFee);

        // 撤单期间被 filled 的场景处理
        boolean spotGotFilled = spotAfterCancel != null && spotAfterCancel.isSuccess();
        boolean perpGotFilled = perpAfterCancel != null && perpAfterCancel.isSuccess();

        if (spotGotFilled && perpGotFilled) {
            // 两腿都在 cancel 期间成交（可能都是 partial）
            if (isEntry) {
                // 入场：qty 差异走 balanceLegs
                BigDecimal diff = spotAfterCancel.getExecutedQty()
                        .subtract(perpAfterCancel.getExecutedQty()).abs();
                if (diff.compareTo(qty.multiply(new BigDecimal("0.001"))) <= 0) {
                    return DualMakerOutcome.success(spotAfterCancel, perpAfterCancel, "BOTH_MAKER");
                }
                return balanceLegs(spotAfterCancel, perpAfterCancel, symbol, true,
                        spotCreds, perpCreds, spotTakerFee, perpTakerFee);
            } else {
                // 出场：把 remainder 都平掉
                return exitCloseRemainders(spotAfterCancel, perpAfterCancel, symbol,
                        spotQty, perpQty, spotCreds, perpCreds, spotTakerFee, perpTakerFee);
            }
        }
        if (spotGotFilled) {
            // Spot 有成交，perp 完全没成
            //   入场：chase perp = spot 实际量（delta-neutral）
            //   出场：chase perp = pair.perpQty（清完 perp 短头寸）；spot 剩余也要平
            BigDecimal chasePerpQty = isEntry ? spotAfterCancel.getExecutedQty() : perpQty;
            PairOrderResult perpMarket = isEntry
                    ? orderClient.perpSellMarket(perpCreds.apiKey(), perpCreds.apiSecret(), symbol, chasePerpQty, perpTakerFee)
                    : orderClient.perpBuyReduceOnly(perpCreds.apiKey(), perpCreds.apiSecret(), symbol, chasePerpQty, perpTakerFee);
            if (!perpMarket.isSuccess()) {
                return DualMakerOutcome.partialLegRisk(spotAfterCancel, "TIMEOUT_SPOT_FILLED_PERP_MKT_FAIL");
            }
            if (!isEntry) {
                BigDecimal spotRemaining = spotQty.subtract(spotAfterCancel.getExecutedQty());
                if (spotRemaining.signum() > 0) {
                    PairOrderResult spotExtra = orderClient.spotSellMarket(spotCreds.apiKey(),
                            spotCreds.apiSecret(), symbol, spotRemaining, spotTakerFee);
                    if (!spotExtra.isSuccess()) {
                        return DualMakerOutcome.partialLegRisk(spotAfterCancel, "TIMEOUT_EXIT_SPOT_REM_FAIL");
                    }
                    spotAfterCancel = mergeFill(spotAfterCancel, spotExtra);
                }
            }
            return DualMakerOutcome.success(spotAfterCancel, perpMarket, "SPOT_MAKER");
        }
        if (perpGotFilled) {
            BigDecimal chaseSpotQty = isEntry ? perpAfterCancel.getExecutedQty() : spotQty;
            PairOrderResult spotMarket = isEntry
                    ? orderClient.spotBuyMarket(spotCreds.apiKey(), spotCreds.apiSecret(), symbol, chaseSpotQty, spotTakerFee)
                    : orderClient.spotSellMarket(spotCreds.apiKey(), spotCreds.apiSecret(), symbol, chaseSpotQty, spotTakerFee);
            if (!spotMarket.isSuccess()) {
                return DualMakerOutcome.partialLegRisk(perpAfterCancel, "TIMEOUT_PERP_FILLED_SPOT_MKT_FAIL");
            }
            if (!isEntry) {
                BigDecimal perpRemaining = perpQty.subtract(perpAfterCancel.getExecutedQty());
                if (perpRemaining.signum() > 0) {
                    PairOrderResult perpExtra = orderClient.perpBuyReduceOnly(perpCreds.apiKey(),
                            perpCreds.apiSecret(), symbol, perpRemaining, perpTakerFee);
                    if (!perpExtra.isSuccess()) {
                        return DualMakerOutcome.partialLegRisk(perpAfterCancel, "TIMEOUT_EXIT_PERP_REM_FAIL");
                    }
                    perpAfterCancel = mergeFill(perpAfterCancel, perpExtra);
                }
            }
            return DualMakerOutcome.success(spotMarket, perpAfterCancel, "PERP_MAKER");
        }

        log.warn("[DualMaker] timeout, both canceled sym={} → fallback to full taker", symbol);
        return DualMakerOutcome.needsFallback("timeout");
    }

    /**
     * balanceLegs 前 re-query 一次，拿真正最终的 executedQty。
     */
    private PairOrderResult reQueryFinal(boolean isSpot, FarbCredentialResolver.Credentials creds,
                                          String symbol, PairOrderResult placed,
                                          BigDecimal makerFee, PairOrderResult fallback) {
        if (placed == null || !placed.isSuccess() || placed.getOrderId() == null) return fallback;
        JSONObject q = isSpot
                ? orderClient.spotQueryOrder(creds.apiKey(), creds.apiSecret(), symbol, placed.getOrderId())
                : orderClient.perpQueryOrder(creds.apiKey(), creds.apiSecret(), symbol, placed.getOrderId());
        return updatedIfLarger(q, fallback, makerFee, !isSpot);
    }

    /**
     * 从新查询构造 result，但只有当新 executedQty >= 旧值时才替换。
     * 关键：cancel 之后 status 可能是 CANCELED，但 executedQty 仍是撤前累计的（>= 旧的 partial 值）。
     * 我们必须接受 CANCELED with qty > 0 作为"最终成交量"，否则 delta 漂移。
     * executedQty 只增不减 —— 如果新查询返回更小值（HTTP 失败或延迟），保留旧值更安全。
     */
    private PairOrderResult updatedIfLarger(JSONObject q, PairOrderResult old,
                                              BigDecimal makerFee, boolean isFutures) {
        if (q == null) return old;
        BigDecimal newQty = q.getBigDecimal("executedQty");
        if (newQty == null || newQty.signum() <= 0) return old;
        BigDecimal oldQty = old != null ? old.getExecutedQty() : BigDecimal.ZERO;
        if (newQty.compareTo(oldQty) < 0) return old;   // 新值不该更小
        return buildFilledResult(q, makerFee, isFutures);
    }

    /**
     * 有实际成交（executedQty > 0）就算 fillable，无论 status：
     *   - FILLED：完全成交
     *   - PARTIALLY_FILLED：部分成交（还在盘中）
     *   - CANCELED：被撤（可能是我们主动撤 OR winner 撤对方），但撤前有 partial 成交
     * 只要 executedQty > 0，那部分成交是真实存在的，必须记录，否则 delta 漂移或超量下单。
     */
    private boolean isFillableStatus(JSONObject q) {
        if (q == null) return false;
        BigDecimal executed = q.getBigDecimal("executedQty");
        if (executed == null || executed.signum() <= 0) return false;
        String status = q.getString("status");
        return "FILLED".equals(status)
                || "PARTIALLY_FILLED".equals(status)
                || "CANCELED".equals(status)
                || "EXPIRED".equals(status);   // 部分成交后 EXPIRED（如 IOC 型）
    }


    /**
     * 撤单 + 再查一次确认状态。
     * 关键：cancel 后 status 通常是 CANCELED，但 executedQty > 0 表示撤单前已成交部分。
     * 必须识别这种情况，不能只看 FILLED/PARTIAL — 否则 fallback 全 taker 会重复下单。
     */
    private PairOrderResult safeCancelAndConfirm(boolean isSpot,
                                                   FarbCredentialResolver.Credentials creds,
                                                   String symbol, PairOrderResult placed,
                                                   BigDecimal makerFee) {
        if (placed == null || !placed.isSuccess() || placed.getOrderId() == null) return null;
        JSONObject q;
        if (isSpot) {
            orderClient.spotCancel(creds.apiKey(), creds.apiSecret(), symbol, placed.getOrderId());
            q = orderClient.spotQueryOrder(creds.apiKey(), creds.apiSecret(),
                    symbol, placed.getOrderId());
        } else {
            orderClient.perpCancel(creds.apiKey(), creds.apiSecret(), symbol, placed.getOrderId());
            q = orderClient.perpQueryOrder(creds.apiKey(), creds.apiSecret(),
                    symbol, placed.getOrderId());
        }
        if (q == null) return null;
        BigDecimal executedQty = q.getBigDecimal("executedQty");
        if (executedQty == null || executedQty.signum() <= 0) return null;
        log.warn("[DualMaker] cancel race: {} order {} had executedQty={} status={}",
                isSpot ? "spot" : "perp", placed.getOrderId(), executedQty, q.getString("status"));
        return buildFilledResult(q, makerFee, !isSpot);
    }

    /**
     * 出场：两腿都有成交但可能是 partial，需要把 (spotQty - spotFilled) 和 (perpQty - perpFilled) 都用市价平掉。
     * 只要有一腿 remainder 平不掉 → LEG_RISK。
     */
    private DualMakerOutcome exitCloseRemainders(PairOrderResult spot, PairOrderResult perp,
                                                   String symbol,
                                                   BigDecimal spotQty, BigDecimal perpQty,
                                                   FarbCredentialResolver.Credentials spotCreds,
                                                   FarbCredentialResolver.Credentials perpCreds,
                                                   BigDecimal spotTakerFee, BigDecimal perpTakerFee) {
        BigDecimal spotRemaining = spotQty.subtract(spot.getExecutedQty());
        BigDecimal perpRemaining = perpQty.subtract(perp.getExecutedQty());

        if (spotRemaining.signum() > 0) {
            PairOrderResult extra = orderClient.spotSellMarket(
                    spotCreds.apiKey(), spotCreds.apiSecret(),
                    symbol, spotRemaining, spotTakerFee);
            if (!extra.isSuccess()) {
                return DualMakerOutcome.partialLegRisk(spot, "EXIT_SPOT_REMAINDER_FAIL");
            }
            spot = mergeFill(spot, extra);
        }
        if (perpRemaining.signum() > 0) {
            PairOrderResult extra = orderClient.perpBuyReduceOnly(
                    perpCreds.apiKey(), perpCreds.apiSecret(),
                    symbol, perpRemaining, perpTakerFee);
            if (!extra.isSuccess()) {
                return DualMakerOutcome.partialLegRisk(perp, "EXIT_PERP_REMAINDER_FAIL");
            }
            perp = mergeFill(perp, extra);
        }
        String route = (spotRemaining.signum() == 0 && perpRemaining.signum() == 0)
                ? "BOTH_MAKER" : "BOTH_MAKER_WITH_REMAINDER";
        log.info("[DualMaker] exit sym={} route={} spotFinal={} perpFinal={}",
                symbol, route, spot.getExecutedQty(), perp.getExecutedQty());
        return DualMakerOutcome.success(spot, perp, route);
    }

    /** 两腿都成但数量不齐 → 追差额（发生在两腿几乎同时被 partial-filled 时）*/
    private DualMakerOutcome balanceLegs(PairOrderResult spot, PairOrderResult perp,
                                          String symbol, boolean isEntry,
                                          FarbCredentialResolver.Credentials spotCreds,
                                          FarbCredentialResolver.Credentials perpCreds,
                                          BigDecimal spotTakerFee, BigDecimal perpTakerFee) {
        BigDecimal spotQ = spot.getExecutedQty();
        BigDecimal perpQ = perp.getExecutedQty();
        int cmp = spotQ.compareTo(perpQ);
        if (cmp > 0) {
            // spot 多，perp 少 → 追 perp
            BigDecimal delta = spotQ.subtract(perpQ);
            PairOrderResult extra = isEntry
                    ? orderClient.perpSellMarket(perpCreds.apiKey(), perpCreds.apiSecret(), symbol, delta, perpTakerFee)
                    : orderClient.perpBuyReduceOnly(perpCreds.apiKey(), perpCreds.apiSecret(), symbol, delta, perpTakerFee);
            if (extra.isSuccess()) {
                // 合并 perp 的 fill（简化：新 result 取 spot qty 为主，fee 累加）
                perp = mergeFill(perp, extra);
                return DualMakerOutcome.success(spot, perp, "BOTH_MAKER_BALANCED");
            }
            return DualMakerOutcome.partialLegRisk(spot, "BOTH_MAKER_BALANCE_PERP_FAIL");
        } else if (cmp < 0) {
            BigDecimal delta = perpQ.subtract(spotQ);
            PairOrderResult extra = isEntry
                    ? orderClient.spotBuyMarket(spotCreds.apiKey(), spotCreds.apiSecret(), symbol, delta, spotTakerFee)
                    : orderClient.spotSellMarket(spotCreds.apiKey(), spotCreds.apiSecret(), symbol, delta, spotTakerFee);
            if (extra.isSuccess()) {
                spot = mergeFill(spot, extra);
                return DualMakerOutcome.success(spot, perp, "BOTH_MAKER_BALANCED");
            }
            return DualMakerOutcome.partialLegRisk(perp, "BOTH_MAKER_BALANCE_SPOT_FAIL");
        }
        return DualMakerOutcome.success(spot, perp, "BOTH_MAKER");
    }

    /** 把追单结果合并进原成交（qty 相加，fee 相加，价格按加权平均）*/
    private PairOrderResult mergeFill(PairOrderResult base, PairOrderResult extra) {
        BigDecimal totalQty = base.getExecutedQty().add(extra.getExecutedQty());
        BigDecimal totalNotional = base.getExecutedQty().multiply(base.getAvgFillPrice())
                .add(extra.getExecutedQty().multiply(extra.getAvgFillPrice()));
        BigDecimal avgPrice = totalQty.compareTo(BigDecimal.ZERO) > 0
                ? totalNotional.divide(totalQty, 8, RoundingMode.HALF_UP)
                : base.getAvgFillPrice();
        return PairOrderResult.builder()
                .success(true)
                .orderId(base.getOrderId())
                .clientOrderId(base.getClientOrderId())
                .executedQty(totalQty)
                .avgFillPrice(avgPrice)
                .feePaid(base.getFeePaid().add(extra.getFeePaid()))
                .build();
    }

    /** 从 Binance queryOrder 响应构造 PairOrderResult（FILLED 或 PARTIALLY_FILLED）*/
    private PairOrderResult buildFilledResult(JSONObject q, BigDecimal makerFeeRate, boolean isFutures) {
        BigDecimal executedQty = q.getBigDecimal("executedQty");
        BigDecimal avgPrice;
        if (isFutures) {
            avgPrice = q.getBigDecimal("avgPrice");
            if (avgPrice == null || avgPrice.compareTo(BigDecimal.ZERO) == 0) {
                avgPrice = q.getBigDecimal("price");
            }
        } else {
            BigDecimal cum = q.getBigDecimal("cummulativeQuoteQty");
            avgPrice = (cum != null && executedQty != null && executedQty.compareTo(BigDecimal.ZERO) > 0)
                    ? cum.divide(executedQty, 8, RoundingMode.HALF_UP)
                    : q.getBigDecimal("price");
        }
        BigDecimal notional = (executedQty != null && avgPrice != null)
                ? executedQty.multiply(avgPrice) : BigDecimal.ZERO;
        BigDecimal fee = makerFeeRate != null ? notional.multiply(makerFeeRate) : BigDecimal.ZERO;
        return PairOrderResult.builder()
                .success(true)
                .orderId(q.getString("orderId"))
                .clientOrderId(q.getString("clientOrderId"))
                .executedQty(executedQty != null ? executedQty : BigDecimal.ZERO)
                .avgFillPrice(avgPrice != null ? avgPrice : BigDecimal.ZERO)
                .feePaid(fee)
                .build();
    }

    // ─── 结果封装 ─────────────────────────────────────────────────────

    public record DualMakerOutcome(
            boolean success,
            boolean needsFallback,
            PairOrderResult spot,
            PairOrderResult perp,
            String route,           // BOTH_MAKER / SPOT_MAKER / PERP_MAKER / FALLBACK / LEG_RISK
            String errorMessage
    ) {
        static DualMakerOutcome success(PairOrderResult spot, PairOrderResult perp, String route) {
            return new DualMakerOutcome(true, false, spot, perp, route, null);
        }
        static DualMakerOutcome needsFallback(String reason) {
            return new DualMakerOutcome(false, true, null, null, "FALLBACK", reason);
        }
        static DualMakerOutcome partialLegRisk(PairOrderResult filled, String reason) {
            return new DualMakerOutcome(false, false, filled, null, "LEG_RISK", reason);
        }
        static DualMakerOutcome failed(String reason) {
            return new DualMakerOutcome(false, false, null, null, "FAIL", reason);
        }
    }
}

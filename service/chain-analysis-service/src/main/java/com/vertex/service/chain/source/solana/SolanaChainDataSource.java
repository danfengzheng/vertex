package com.vertex.service.chain.source.solana;

import com.alibaba.fastjson2.JSONObject;
import com.vertex.service.chain.config.ChainAnalysisProperties;
import com.vertex.service.chain.source.ChainDataSource;
import com.vertex.service.chain.source.NewTokenRawData;
import com.vertex.service.chain.source.bnb.DexScreenerClient;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Solana 链数据源实现
 * <p>
 * 主要数据来源：
 * <ul>
 *   <li>Pump.fun — 最新 meme 代币发现（免费，无需 Key）</li>
 *   <li>DexScreener — 已毕业代币的价格、流动性、买卖压力（免费，无需 Key）</li>
 *   <li>Helius RPC — 持有者数量等链上指标（需要 Key，可选）</li>
 * </ul>
 * <p>
 * Helius Key 缺失时自动降级，不影响数据采集。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "vertex.chain.solana", name = "enabled", havingValue = "true", matchIfMissing = true)
public class SolanaChainDataSource implements ChainDataSource {

    private final ChainAnalysisProperties properties;
    private final HeliusClient heliusClient;
    private final JupiterClient jupiterClient;
    private final PumpFunClient pumpFunClient;
    private final DexScreenerClient dexScreenerClient;

    public SolanaChainDataSource(
            ChainAnalysisProperties properties,
            @Qualifier("chainOkHttpClient") OkHttpClient httpClient) {
        this.properties = properties;
        ChainAnalysisProperties.Solana solCfg = properties.getSolana();
        this.heliusClient = new HeliusClient(httpClient, solCfg.getHeliusRpcUrl(), solCfg.getHeliusApiKey());
        this.jupiterClient = new JupiterClient(httpClient, solCfg.getJupiterApiUrl());
        this.pumpFunClient = new PumpFunClient(httpClient, solCfg.getPumpfunApiUrl());
        this.dexScreenerClient = new DexScreenerClient(httpClient);
    }

    @Override
    public String chainCode() {
        return "SOL";
    }

    /**
     * Pump.fun 和 DexScreener 均无需 Key，始终可用。
     * Helius Key 缺失时仅降级（持有者数量缺失），不影响整体可用性。
     */
    @Override
    public boolean isAvailable() {
        ChainAnalysisProperties.Solana solCfg = properties.getSolana();
        boolean hasHeliusKey = solCfg.getHeliusApiKey() != null && !solCfg.getHeliusApiKey().isBlank();
        if (!hasHeliusKey) {
            log.debug("[SOL] Helius Key 未配置，持有者数量将不可用（其他数据正常）");
        }
        return true; // Pump.fun + DexScreener 无需 Key，始终可用
    }

    @Override
    public List<NewTokenRawData> fetchNewTokens(int scanWindowMinutes) {
        if (!isAvailable()) return Collections.emptyList();

        try {
            long windowMs = (long) scanWindowMinutes * 60 * 1000;
            long since = System.currentTimeMillis() - windowMs;
            double minLiquidity = properties.getSolana().getMinLiquidityUsd();

            // 1. 从 Pump.fun 获取最新代币
            List<JSONObject> pumpTokens = pumpFunClient.fetchLatestTokens(50);
            if (pumpTokens.isEmpty()) {
                log.debug("[SOL] No new tokens from Pump.fun");
                return Collections.emptyList();
            }

            List<NewTokenRawData> result = new ArrayList<>();
            for (JSONObject token : pumpTokens) {
                try {
                    // 过滤时间窗口
                    long createdTs = token.getLongValue("created_timestamp");
                    if (createdTs < since) continue;

                    NewTokenRawData data = buildFromPumpFun(token, minLiquidity);
                    if (data != null) result.add(data);
                } catch (Exception e) {
                    log.warn("[SOL] Failed to process token {}: {}", token.getString("mint"), e.getMessage());
                }
            }
            log.info("[SOL] Fetched {} new tokens (from {} Pump.fun entries)", result.size(), pumpTokens.size());
            return result;
        } catch (Exception e) {
            log.error("[SOL] fetchNewTokens error: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    // ─── 内部处理 ──────────────────────────────────────────

    private NewTokenRawData buildFromPumpFun(JSONObject token, double minLiquidity) {
        String mintAddress = token.getString("mint");
        if (mintAddress == null) return null;

        boolean graduated = Boolean.TRUE.equals(token.getBoolean("complete"));
        BigDecimal usdMarketCap = token.getBigDecimal("usd_market_cap");

        // ── 1. 价格和流动性：优先 DexScreener（毕业后才有真实 DEX pair）──────
        BigDecimal priceUsd = null;
        BigDecimal liquidityUsd = null;
        BigDecimal volume24h = null;
        BigDecimal priceChange1h = null;
        BigDecimal priceChange24h = null;
        BigDecimal buyPressure = BigDecimal.valueOf(0.6);
        String pairAddress = null;
        int txCount1hDex = 0;

        if (graduated) {
            // 毕业后有 Raydium pair，从 DexScreener 获取精确市场数据
            List<JSONObject> pairs = dexScreenerClient.fetchTokenPairs("solana", mintAddress);
            if (!pairs.isEmpty()) {
                JSONObject bestPair = selectBestPair(pairs);
                if (bestPair != null) {
                    priceUsd = parseBigDecimal(bestPair.getString("priceUsd"));
                    pairAddress = bestPair.getString("pairAddress");
                    JSONObject liq = bestPair.getJSONObject("liquidity");
                    if (liq != null) liquidityUsd = liq.getBigDecimal("usd");
                    JSONObject vol = bestPair.getJSONObject("volume");
                    if (vol != null) volume24h = vol.getBigDecimal("h24");
                    JSONObject pc = bestPair.getJSONObject("priceChange");
                    if (pc != null) {
                        priceChange1h = pc.getBigDecimal("h1");
                        priceChange24h = pc.getBigDecimal("h24");
                    }
                    buyPressure = calcBuyPressure(bestPair);
                    JSONObject txns = bestPair.getJSONObject("txns");
                    if (txns != null) {
                        JSONObject h1 = txns.getJSONObject("h1");
                        if (h1 != null) txCount1hDex = h1.getIntValue("buys") + h1.getIntValue("sells");
                    }
                }
            }
        }

        // 回退到 Jupiter 价格
        if (priceUsd == null) {
            priceUsd = jupiterClient.getPrice(mintAddress);
        }

        // 估算流动性（未毕业时用市值的 10% 近似）
        if (liquidityUsd == null && usdMarketCap != null) {
            liquidityUsd = usdMarketCap.multiply(BigDecimal.valueOf(0.1));
        }
        if (liquidityUsd != null && liquidityUsd.doubleValue() < minLiquidity) return null;
        if (!graduated && (usdMarketCap == null || usdMarketCap.doubleValue() < minLiquidity * 2)) return null;

        // ── 2. 链上指标：Helius（有 Key）或估算 ──────────────────────────────
        boolean hasHelius = hasHeliusKey();
        int decimals = hasHelius ? heliusClient.getDecimals(mintAddress) : 9;
        int holderCount = hasHelius ? heliusClient.getTokenHolderCount(mintAddress) : 0;

        long createdTs = token.getLongValue("created_timestamp");
        int ageMinutes = createdTs > 0 ? (int) ((System.currentTimeMillis() - createdTs) / 60_000) : -1;

        // 交易数：DexScreener 数据优先，否则从 Pump.fun reply_count 估算
        int txCount1h = txCount1hDex > 0 ? txCount1hDex : estimateTxCount(token);

        return NewTokenRawData.builder()
                .chain("SOL")
                .contractAddress(mintAddress)
                .symbol(token.getString("symbol"))
                .name(token.getString("name"))
                .decimals(decimals)
                .deployerAddress(token.getString("creator"))
                .pairAddress(pairAddress)
                .listingTimeMs(createdTs > 0 ? createdTs : null)
                .holderCount(holderCount > 0 ? holderCount : null)
                .txCount1h(txCount1h)
                .lpAddCount(graduated ? 2 : 1)
                .liquidityLocked(graduated)  // 毕业后 Pump.fun 自动锁定 LP
                .contractVerified(true)       // Solana SPL Token 无需验证概念
                .priceUsd(priceUsd)
                .marketCapUsd(usdMarketCap)
                .liquidityUsd(liquidityUsd)
                .volume24hUsd(volume24h)
                .priceChange1hPct(priceChange1h)
                .priceChange24hPct(priceChange24h)
                .buyPressure1h(buyPressure)
                .top10HolderPct(null)
                .deployerHoldingPct(null)
                .lpPoolPct(graduated ? BigDecimal.valueOf(20.0) : BigDecimal.valueOf(5.0))
                .ageMinutes(ageMinutes >= 0 ? ageMinutes : null)
                .pumpFunListed(true)
                .build();
    }

    /** 从多个 pair 中选流动性最高的 */
    private JSONObject selectBestPair(List<JSONObject> pairs) {
        JSONObject best = null;
        double bestLiq = -1;
        for (JSONObject p : pairs) {
            JSONObject liq = p.getJSONObject("liquidity");
            double liqUsd = liq != null ? liq.getDoubleValue("usd") : 0;
            if (liqUsd > bestLiq) { bestLiq = liqUsd; best = p; }
        }
        return best;
    }

    private BigDecimal calcBuyPressure(JSONObject pair) {
        try {
            JSONObject txns = pair.getJSONObject("txns");
            if (txns == null) return BigDecimal.valueOf(0.6);
            JSONObject h1 = txns.getJSONObject("h1");
            if (h1 == null) return BigDecimal.valueOf(0.6);
            double buys = h1.getDoubleValue("buys");
            double sells = h1.getDoubleValue("sells");
            double total = buys + sells;
            return total > 0 ? BigDecimal.valueOf(buys / total) : BigDecimal.valueOf(0.5);
        } catch (Exception e) {
            return BigDecimal.valueOf(0.5);
        }
    }

    private BigDecimal parseBigDecimal(String val) {
        if (val == null || val.isBlank()) return null;
        try { return new BigDecimal(val); } catch (Exception e) { return null; }
    }

    private int estimateTxCount(JSONObject token) {
        int replies = token.getIntValue("reply_count");
        return Math.max(replies * 5, 10);
    }

    private boolean hasHeliusKey() {
        String key = properties.getSolana().getHeliusApiKey();
        return key != null && !key.isBlank();
    }
}

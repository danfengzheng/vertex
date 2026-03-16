package com.vertex.service.chain.source.bnb;

import com.alibaba.fastjson2.JSONObject;
import com.vertex.service.chain.config.ChainAnalysisProperties;
import com.vertex.service.chain.source.ChainDataSource;
import com.vertex.service.chain.source.NewTokenRawData;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * BNB Chain 一级市场数据源
 * <p>
 * 专注于 <b>bonding curve 阶段</b>的代币（一级市场），优先使用 Four.meme：
 * <ul>
 *   <li><b>Four.meme</b> — BSC 上的 Pump.fun 等价平台，代币在 bonding curve 填满后
 *       自动毕业并在 PancakeSwap 创建流动性池（无需 Key）</li>
 *   <li><b>DexScreener（备用）</b> — Four.meme 不可用时，
 *       抓取创建时间 &lt; 20 分钟的极早期 BSC 交易对作为近似</li>
 *   <li><b>BSCScan（可选补充）</b> — 有 Key 时提供持有者数量等链上指标</li>
 * </ul>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "vertex.chain.bnb", name = "enabled", havingValue = "true", matchIfMissing = true)
public class BnbChainDataSource implements ChainDataSource {

    /** 极早期 DexScreener 备用扫描窗口（分钟）—— 代表"刚上 DEX"的早期市场 */
    private static final int FALLBACK_MAX_AGE_MINUTES = 20;

    private static final Set<String> QUOTE_SYMBOLS = Set.of(
            "WBNB", "BUSD", "USDT", "USDC", "DAI", "BTCB", "ETH", "CAKE"
    );

    private final ChainAnalysisProperties properties;
    private final FourMemeClient fourMemeClient;
    private final DexScreenerClient dexScreenerClient;
    private final BscScanClient bscScanClient;

    public BnbChainDataSource(
            ChainAnalysisProperties properties,
            @Qualifier("chainOkHttpClient") OkHttpClient httpClient) {
        this.properties = properties;
        ChainAnalysisProperties.Bnb bnbCfg = properties.getBnb();
        this.fourMemeClient = new FourMemeClient(httpClient);
        this.dexScreenerClient = new DexScreenerClient(httpClient);
        this.bscScanClient = new BscScanClient(httpClient, bnbCfg.getBscscanApiUrl(), bnbCfg.getBscscanApiKey());
    }

    @Override
    public String chainCode() {
        return "BNB";
    }

    @Override
    public boolean isAvailable() {
        return true; // Four.meme + DexScreener 均无需 Key
    }

    @Override
    public List<NewTokenRawData> fetchNewTokens(int scanWindowMinutes) {
        try {
            // 优先使用 Four.meme（一级市场，bonding curve）
            List<NewTokenRawData> fourMemeResult = fetchFromFourMeme(scanWindowMinutes);
            if (!fourMemeResult.isEmpty()) {
                log.info("[BNB] Four.meme: {} primary market tokens", fourMemeResult.size());
                return fourMemeResult;
            }

            // Four.meme 不可用时，退化到 DexScreener 极早期交易对（< 20 分钟）
            log.info("[BNB] Four.meme unavailable, falling back to DexScreener (< {}min pairs)", FALLBACK_MAX_AGE_MINUTES);
            return fetchFromDexScreenerEarlyPairs();
        } catch (Exception e) {
            log.error("[BNB] fetchNewTokens error: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    // ─── Four.meme 一级市场 ──────────────────────────────────

    private List<NewTokenRawData> fetchFromFourMeme(int scanWindowMinutes) {
        List<JSONObject> tokens = fourMemeClient.fetchNewTokens(100);
        if (tokens.isEmpty()) return Collections.emptyList();

        long sinceMs = System.currentTimeMillis() - (long) scanWindowMinutes * 60_000;
        double minMarketCapUsd = properties.getBnb().getMinLiquidityUsd(); // 复用配置作为市值门槛
        boolean hasBscKey = hasBscScanKey();

        List<NewTokenRawData> result = new ArrayList<>();
        for (JSONObject token : tokens) {
            try {
                NewTokenRawData data = buildFromFourMeme(token, sinceMs, minMarketCapUsd, hasBscKey);
                if (data != null) result.add(data);
            } catch (Exception e) {
                log.warn("[BNB/4meme] Failed to process token: {}", e.getMessage());
            }
        }
        return result;
    }

    /**
     * 从 Four.meme API 响应构建 NewTokenRawData。
     * <p>
     * Four.meme API 字段（已知字段，可能随版本变化）：
     * <pre>
     * {
     *   "tokenAddress":  "0x...",
     *   "tokenName":     "FunToken",
     *   "tokenSymbol":   "FUN",
     *   "decimals":      18,
     *   "creator":       "0x...",
     *   "createTime":    1700000000000,  // ms
     *   "lastTradeTime": 1700000001000,
     *   "totalSupply":   "1000000000000000000",
     *   "marketCap":     12500.0,        // USD
     *   "progress":      48.6,           // bonding curve 进度 %
     *   "commentCount":  32,
     *   "status":        1               // 1=active, 2=graduated
     * }
     * </pre>
     */
    NewTokenRawData buildFromFourMeme(JSONObject token, long sinceMs,
                                             double minMarketCapUsd, boolean hasBscKey) {
        // 只处理未毕业（status != 2）的代币
        int status = token.getIntValue("status");
        if (status == 2) return null; // 已毕业 = 已上 PancakeSwap = 二级市场

        // 合约地址（Four.meme 字段名可能有变化，兼容多个）
        String address = firstNonNull(
                token.getString("tokenAddress"),
                token.getString("address"),
                token.getString("contractAddress")
        );
        if (address == null || address.isBlank()) return null;

        // 创建时间过滤
        long createTime = firstLong(
                token.getLong("createTime"),
                token.getLong("created_timestamp"),
                token.getLong("createAt")
        );
        if (createTime > 0 && createTime < sinceMs) return null;

        // 市值过滤（一级市场用市值代替流动性）
        double marketCapUsd = firstDouble(
                token.getDouble("marketCap"),
                token.getDouble("market_cap"),
                token.getDouble("usd_market_cap")
        );
        if (marketCapUsd < minMarketCapUsd) return null;

        // Bonding curve 进度（0-100%）
        double bondingPct = firstDouble(
                token.getDouble("progress"),
                token.getDouble("bondingCurveProgress"),
                token.getDouble("bonding_curve_progress")
        );

        // 社区评论数
        int replyCount = firstInt(
                token.getInteger("commentCount"),
                token.getInteger("comment_count"),
                token.getInteger("replyCount"),
                token.getInteger("reply_count")
        );

        // 时间
        int ageMinutes = createTime > 0
                ? (int) ((System.currentTimeMillis() - createTime) / 60_000) : -1;

        // 符号 / 名称
        String symbol = firstNonNull(token.getString("tokenSymbol"), token.getString("symbol"));
        String name   = firstNonNull(token.getString("tokenName"),   token.getString("name"));

        // 价格
        BigDecimal priceUsd = null;
        String priceStr = firstNonNull(token.getString("price"), token.getString("priceUsd"));
        if (priceStr != null) priceUsd = parseBigDecimal(priceStr);

        // 可选 BSCScan 链上数据
        int holderCount = 0;
        if (hasBscKey) {
            holderCount = bscScanClient.getHolderCount(address);
        }

        return NewTokenRawData.builder()
                .chain("BNB")
                .contractAddress(address)
                .symbol(symbol)
                .name(name)
                .decimals(token.getInteger("decimals"))
                .deployerAddress(firstNonNull(token.getString("creator"), token.getString("deployer")))
                .pairAddress(null)          // 未毕业：无 DEX 交易对
                .listingTimeMs(createTime > 0 ? createTime : null)
                // ── 链上 ──
                .holderCount(holderCount > 0 ? holderCount : null)
                .txCount1h(null)
                .lpAddCount(0)
                .liquidityLocked(false)
                .contractVerified(null)     // Four.meme 不提供合约验证状态
                // ── 市场（bonding curve 阶段，无 DEX 数据）──
                .priceUsd(priceUsd)
                .marketCapUsd(BigDecimal.valueOf(marketCapUsd))
                .liquidityUsd(null)
                .volume24hUsd(null)
                .priceChange1hPct(null)
                .priceChange24hPct(null)
                .buyPressure1h(null)
                // ── 代币经济 ──
                .top10HolderPct(null)
                .deployerHoldingPct(null)
                .lpPoolPct(null)
                // ── 新颖度 ──
                .ageMinutes(ageMinutes >= 0 ? ageMinutes : null)
                .pumpFunListed(false)
                // ── 一级市场专属 ──
                .bondingCurveProgress(bondingPct > 0 ? bondingPct : null)
                .replyCount(replyCount)
                .launchpadName("four.meme")
                .dataSource("bnb_primary")
                .build();
    }

    // ─── DexScreener 备用（极早期交易对）──────────────────────

    private List<NewTokenRawData> fetchFromDexScreenerEarlyPairs() {
        List<JSONObject> pairs = dexScreenerClient.searchNewPairs("bsc", 100);
        if (pairs.isEmpty()) return Collections.emptyList();

        long sinceMs = System.currentTimeMillis() - (long) FALLBACK_MAX_AGE_MINUTES * 60_000;
        double minLiquidity = properties.getBnb().getMinLiquidityUsd();
        boolean hasBscKey = hasBscScanKey();

        List<NewTokenRawData> result = new ArrayList<>();
        for (JSONObject pair : pairs) {
            try {
                long createdAt = pair.getLongValue("pairCreatedAt");
                if (createdAt > 0 && createdAt < sinceMs) continue; // 只取 < 20 分钟的

                NewTokenRawData data = buildFromDexScreenerPair(pair, minLiquidity, hasBscKey);
                if (data != null) result.add(data);
            } catch (Exception e) {
                log.warn("[BNB/DexScreener] Failed to process pair: {}", e.getMessage());
            }
        }
        log.info("[BNB] DexScreener fallback: {} early-stage tokens (< {}min)", result.size(), FALLBACK_MAX_AGE_MINUTES);
        return result;
    }

    private NewTokenRawData buildFromDexScreenerPair(JSONObject pair, double minLiquidity, boolean hasBscKey) {
        JSONObject base  = pair.getJSONObject("baseToken");
        if (base == null) return null;

        String baseSymbol = base.getString("symbol");
        if (baseSymbol != null && QUOTE_SYMBOLS.contains(baseSymbol.toUpperCase())) return null;

        String contractAddress = base.getString("address");
        if (contractAddress == null || contractAddress.isBlank()) return null;

        JSONObject liquidity = pair.getJSONObject("liquidity");
        BigDecimal liquidityUsd = liquidity != null ? liquidity.getBigDecimal("usd") : null;
        if (liquidityUsd == null || liquidityUsd.doubleValue() < minLiquidity) return null;

        long createdAt = pair.getLongValue("pairCreatedAt");
        int ageMinutes = createdAt > 0 ? (int)((System.currentTimeMillis() - createdAt) / 60_000) : -1;

        BigDecimal priceUsd      = parseBigDecimal(pair.getString("priceUsd"));
        JSONObject priceChange   = pair.getJSONObject("priceChange");
        BigDecimal priceChange1h = priceChange != null ? priceChange.getBigDecimal("h1") : null;
        BigDecimal priceChange24h = priceChange != null ? priceChange.getBigDecimal("h24") : null;
        JSONObject volume        = pair.getJSONObject("volume");
        BigDecimal volume24h     = volume != null ? volume.getBigDecimal("h24") : null;
        BigDecimal buyPressure   = calcBuyPressure(pair);
        BigDecimal marketCapUsd  = pair.getBigDecimal("marketCap");

        int holderCount = 0, txCount1h = 0;
        boolean verified = false;
        if (hasBscKey) {
            holderCount = bscScanClient.getHolderCount(contractAddress);
            txCount1h   = bscScanClient.getTxCount1h(contractAddress);
            verified    = bscScanClient.isContractVerified(contractAddress);
        } else {
            JSONObject txns = pair.getJSONObject("txns");
            if (txns != null) {
                JSONObject h1 = txns.getJSONObject("h1");
                if (h1 != null) txCount1h = h1.getIntValue("buys") + h1.getIntValue("sells");
            }
        }

        return NewTokenRawData.builder()
                .chain("BNB")
                .contractAddress(contractAddress)
                .symbol(baseSymbol)
                .name(base.getString("name"))
                .pairAddress(pair.getString("pairAddress"))
                .listingTimeMs(createdAt > 0 ? createdAt : null)
                .holderCount(holderCount > 0 ? holderCount : null)
                .txCount1h(txCount1h)
                .lpAddCount(1)
                .liquidityLocked(false)
                .contractVerified(hasBscKey ? verified : null)
                .priceUsd(priceUsd)
                .marketCapUsd(marketCapUsd)
                .liquidityUsd(liquidityUsd)
                .volume24hUsd(volume24h)
                .priceChange1hPct(priceChange1h)
                .priceChange24hPct(priceChange24h)
                .buyPressure1h(buyPressure)
                .ageMinutes(ageMinutes >= 0 ? ageMinutes : null)
                .pumpFunListed(false)
                .bondingCurveProgress(null)  // DexScreener 代币已上 DEX，无 bonding curve
                .replyCount(null)
                .launchpadName(null)
                .dataSource("bnb_primary")
                .build();
    }

    // ─── 工具方法 ──────────────────────────────────────────

    private BigDecimal calcBuyPressure(JSONObject pair) {
        try {
            JSONObject txns = pair.getJSONObject("txns");
            if (txns == null) return BigDecimal.valueOf(0.5);
            JSONObject h1 = txns.getJSONObject("h1");
            if (h1 == null) return BigDecimal.valueOf(0.5);
            double buys = h1.getDoubleValue("buys");
            double sells = h1.getDoubleValue("sells");
            double total = buys + sells;
            return total > 0 ? BigDecimal.valueOf(buys / total) : BigDecimal.valueOf(0.5);
        } catch (Exception e) {
            return BigDecimal.valueOf(0.5);
        }
    }

    private boolean hasBscScanKey() {
        String key = properties.getBnb().getBscscanApiKey();
        return key != null && !key.isBlank();
    }

    private BigDecimal parseBigDecimal(String val) {
        if (val == null || val.isBlank()) return null;
        try { return new BigDecimal(val); } catch (Exception e) { return null; }
    }

    // ─── 多候选字段取值辅助 ───────────────────────────────

    private String firstNonNull(String... vals) {
        for (String v : vals) if (v != null && !v.isBlank()) return v;
        return null;
    }

    private long firstLong(Long... vals) {
        for (Long v : vals) if (v != null && v > 0) return v;
        return 0L;
    }

    private double firstDouble(Double... vals) {
        for (Double v : vals) if (v != null && v > 0) return v;
        return 0.0;
    }

    private int firstInt(Integer... vals) {
        for (Integer v : vals) if (v != null && v > 0) return v;
        return 0;
    }
}

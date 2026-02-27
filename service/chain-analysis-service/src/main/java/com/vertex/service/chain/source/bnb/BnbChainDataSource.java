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
 * BNB Chain 数据源实现
 * <p>
 * 主要数据来源：DexScreener（完全免费，无需 API Key），
 * 可选补充：BSCScan（需要 API Key，用于持有者数量等链上指标）。
 * <p>
 * 降级策略：BSCScan Key 未配置时，仅使用 DexScreener 数据，
 * 链上指标字段缺失，评分器会给出默认中间分。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "vertex.chain.bnb", name = "enabled", havingValue = "true", matchIfMissing = true)
public class BnbChainDataSource implements ChainDataSource {

    private static final Set<String> QUOTE_SYMBOLS = Set.of(
            "WBNB", "BUSD", "USDT", "USDC", "DAI", "BTCB", "ETH", "CAKE"
    );

    private final ChainAnalysisProperties properties;
    private final DexScreenerClient dexScreenerClient;
    private final BscScanClient bscScanClient;   // 可选，Key 为空时跳过

    public BnbChainDataSource(
            ChainAnalysisProperties properties,
            @Qualifier("chainOkHttpClient") OkHttpClient httpClient) {
        this.properties = properties;
        ChainAnalysisProperties.Bnb bnbCfg = properties.getBnb();
        this.dexScreenerClient = new DexScreenerClient(httpClient);
        // BSCScan 客户端始终构建，但 Key 为空时调用会快速返回 0/false
        this.bscScanClient = new BscScanClient(httpClient, bnbCfg.getBscscanApiUrl(), bnbCfg.getBscscanApiKey());
    }

    @Override
    public String chainCode() {
        return "BNB";
    }

    /**
     * DexScreener 无需 Key，始终可用。
     * BSCScan Key 缺失时仅降级（链上指标为默认值），不影响整体可用性。
     */
    @Override
    public boolean isAvailable() {
        return true; // DexScreener 无需任何 Key
    }

    @Override
    public List<NewTokenRawData> fetchNewTokens(int scanWindowMinutes) {
        try {
            double minLiquidity = properties.getBnb().getMinLiquidityUsd();
            boolean hasBscKey = hasBscScanKey();

            // 1. 从 DexScreener 获取 BSC 最新交易对
            List<JSONObject> pairs = dexScreenerClient.searchNewPairs("bsc", 100);
            if (pairs.isEmpty()) {
                log.debug("[BNB] No pairs from DexScreener");
                return Collections.emptyList();
            }

            long sinceMs = System.currentTimeMillis() - (long) scanWindowMinutes * 60 * 1000;
            List<NewTokenRawData> result = new ArrayList<>();

            for (JSONObject pair : pairs) {
                try {
                    // 过滤时间窗口（pairCreatedAt 单位：毫秒）
                    long createdAt = pair.getLongValue("pairCreatedAt");
                    if (createdAt > 0 && createdAt < sinceMs) continue;

                    NewTokenRawData data = buildFromDexScreenerPair(pair, minLiquidity, hasBscKey);
                    if (data != null) result.add(data);
                } catch (Exception e) {
                    log.warn("[BNB] Failed to process pair {}: {}", pair.getString("pairAddress"), e.getMessage());
                }
            }
            log.info("[BNB] Fetched {} new tokens (bscKey={})", result.size(), hasBscKey);
            return result;
        } catch (Exception e) {
            log.error("[BNB] fetchNewTokens error: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    // ─── 内部处理 ──────────────────────────────────────────

    /**
     * 从 DexScreener pair 对象构建 NewTokenRawData
     * <p>
     * DexScreener pair 结构示例：
     * <pre>
     * {
     *   "chainId": "bsc",
     *   "dexId": "pancakeswap",
     *   "pairAddress": "0x...",
     *   "baseToken": {"address": "0x...", "name": "Foo", "symbol": "FOO"},
     *   "quoteToken": {"address": "0x...", "symbol": "WBNB"},
     *   "priceUsd": "0.0001234",
     *   "priceChange": {"h1": 5.2, "h24": -3.1},
     *   "liquidity": {"usd": 15000, "base": 1000000, "quote": 50},
     *   "volume": {"h24": 8000},
     *   "txns": {"h1": {"buys": 20, "sells": 8}},
     *   "pairCreatedAt": 1700000000000,
     *   "info": {"imageUrl": "..."}
     * }
     * </pre>
     */
    private NewTokenRawData buildFromDexScreenerPair(JSONObject pair, double minLiquidity, boolean hasBscKey) {
        // 取 baseToken 作为新代币（quoteToken 通常是 WBNB/USDT 等稳定币）
        JSONObject base = pair.getJSONObject("baseToken");
        JSONObject quote = pair.getJSONObject("quoteToken");
        if (base == null) return null;

        // 如果 baseToken 是报价代币，跳过
        String baseSymbol = base.getString("symbol");
        if (baseSymbol != null && QUOTE_SYMBOLS.contains(baseSymbol.toUpperCase())) return null;

        String contractAddress = base.getString("address");
        if (contractAddress == null || contractAddress.isBlank()) return null;

        // 流动性过滤
        JSONObject liquidity = pair.getJSONObject("liquidity");
        BigDecimal liquidityUsd = liquidity != null ? liquidity.getBigDecimal("usd") : null;
        if (liquidityUsd == null || liquidityUsd.doubleValue() < minLiquidity) return null;

        // 时间相关
        long createdAt = pair.getLongValue("pairCreatedAt"); // 毫秒
        int ageMinutes = createdAt > 0
                ? (int) ((System.currentTimeMillis() - createdAt) / 60_000)
                : -1;

        // 价格信息
        BigDecimal priceUsd = parseBigDecimal(pair.getString("priceUsd"));

        // 价格变化（DexScreener 直接提供）
        JSONObject priceChange = pair.getJSONObject("priceChange");
        BigDecimal priceChange1h = priceChange != null ? priceChange.getBigDecimal("h1") : null;
        BigDecimal priceChange24h = priceChange != null ? priceChange.getBigDecimal("h24") : null;

        // 成交量
        JSONObject volume = pair.getJSONObject("volume");
        BigDecimal volume24h = volume != null ? volume.getBigDecimal("h24") : null;

        // 买盘压力（buys / (buys + sells)）
        BigDecimal buyPressure = calcBuyPressure(pair);

        // 市值（DexScreener 部分 pair 提供）
        JSONObject marketCap = pair.getJSONObject("marketCap");
        BigDecimal marketCapUsd = marketCap != null ? marketCap.getBigDecimal("usd") : null;
        if (marketCapUsd == null) {
            marketCapUsd = pair.getBigDecimal("marketCap"); // 有时是直接字段
        }

        // 可选：BSCScan 链上指标
        int holderCount = 0;
        int txCount1h = 0;
        boolean verified = false;
        if (hasBscKey) {
            holderCount = bscScanClient.getHolderCount(contractAddress);
            txCount1h = bscScanClient.getTxCount1h(contractAddress);
            verified = bscScanClient.isContractVerified(contractAddress);
        } else {
            // 无 BSCScan Key 时用 txns 数量估算
            JSONObject txns = pair.getJSONObject("txns");
            if (txns != null) {
                JSONObject h1 = txns.getJSONObject("h1");
                if (h1 != null) {
                    txCount1h = h1.getIntValue("buys") + h1.getIntValue("sells");
                }
            }
        }

        return NewTokenRawData.builder()
                .chain("BNB")
                .contractAddress(contractAddress)
                .symbol(baseSymbol)
                .name(base.getString("name"))
                .decimals(null) // DexScreener 不提供精度
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
                .top10HolderPct(null)
                .deployerHoldingPct(null)
                .lpPoolPct(null)
                .ageMinutes(ageMinutes >= 0 ? ageMinutes : null)
                .pumpFunListed(false)
                .build();
    }

    /** 计算买盘压力比例（0~1） */
    private BigDecimal calcBuyPressure(JSONObject pair) {
        try {
            JSONObject txns = pair.getJSONObject("txns");
            if (txns == null) return BigDecimal.valueOf(0.5);
            JSONObject h1 = txns.getJSONObject("h1");
            if (h1 == null) return BigDecimal.valueOf(0.5);
            double buys = h1.getDoubleValue("buys");
            double sells = h1.getDoubleValue("sells");
            double total = buys + sells;
            if (total <= 0) return BigDecimal.valueOf(0.5);
            return BigDecimal.valueOf(buys / total);
        } catch (Exception e) {
            return BigDecimal.valueOf(0.5);
        }
    }

    private BigDecimal parseBigDecimal(String val) {
        if (val == null || val.isBlank()) return null;
        try { return new BigDecimal(val); } catch (Exception e) { return null; }
    }

    private boolean hasBscScanKey() {
        String key = properties.getBnb().getBscscanApiKey();
        return key != null && !key.isBlank();
    }
}

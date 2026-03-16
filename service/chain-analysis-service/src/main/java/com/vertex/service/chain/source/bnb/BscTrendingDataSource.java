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
 * BSC DEX 动量趋势筛选数据源
 * <p>
 * 通过 DexScreener API 筛选 BSC 链上具备高换手率和正向价格动量的山寨币，
 * 不限制代币年龄，关注当下趋势而非"是否新发"。
 * <p>
 * 筛选逻辑：
 * <ul>
 *   <li>从 DexScreener 获取近期活跃的 BSC 交易对（72h 窗口）</li>
 *   <li>按 volume24h/liquidity 换手率降序排列</li>
 *   <li>过滤：换手率 ≥ minVolumeLiquidityRatio、流动性 ≥ minLiquidityUsd</li>
 *   <li>市值区间：minMarketCapUsd ~ maxMarketCapUsd（排除巨盘和垃圾小币）</li>
 *   <li>1h 价格涨幅 ≥ minPriceChange1hPct（过滤下跌趋势）</li>
 * </ul>
 * <p>
 * 以"潜力筛选模式"运行：已在数据库中的代币会刷新指标和评分。<br>
 * 启用配置：{@code vertex.chain.bsc-trending.enabled=true}
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "vertex.chain.bsc-trending", name = "enabled", havingValue = "true")
public class BscTrendingDataSource implements ChainDataSource {

    private static final Set<String> QUOTE_SYMBOLS = Set.of(
            "WBNB", "BNB", "BUSD", "USDT", "USDC", "DAI", "BTCB", "ETH", "CAKE"
    );

    private final ChainAnalysisProperties properties;
    private final DexScreenerClient dexScreenerClient;
    private final BscScanClient bscScanClient;

    public BscTrendingDataSource(
            ChainAnalysisProperties properties,
            @Qualifier("chainOkHttpClient") OkHttpClient httpClient) {
        this.properties = properties;
        ChainAnalysisProperties.Bnb bnbCfg = properties.getBnb();
        this.dexScreenerClient = new DexScreenerClient(httpClient);
        this.bscScanClient = new BscScanClient(httpClient, bnbCfg.getBscscanApiUrl(), bnbCfg.getBscscanApiKey());
    }

    @Override
    public String chainCode() {
        return "BNB";
    }

    @Override
    public String sourceId() {
        return "bnb_trending";
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public boolean isScreeningMode() {
        return true; // 潜力筛选模式：允许刷新已存在代币的指标
    }

    @Override
    public List<NewTokenRawData> fetchNewTokens(int scanWindowMinutes) {
        ChainAnalysisProperties.BscTrending cfg = properties.getBscTrending();
        try {
            List<JSONObject> pairs = dexScreenerClient.fetchTrendingPairs("bsc", cfg.getLimit() * 3);
            if (pairs.isEmpty()) {
                log.info("[BscTrending] No trending pairs returned");
                return Collections.emptyList();
            }

            boolean hasBscKey = hasBscScanKey();
            List<NewTokenRawData> result = new ArrayList<>();

            for (JSONObject pair : pairs) {
                if (result.size() >= cfg.getLimit()) break;
                try {
                    NewTokenRawData data = buildFromPair(pair, cfg, hasBscKey);
                    if (data != null) result.add(data);
                } catch (Exception e) {
                    log.warn("[BscTrending] Failed to process pair: {}", e.getMessage());
                }
            }

            log.info("[BscTrending] Filtered {} trending tokens from {} candidates", result.size(), pairs.size());
            return result;
        } catch (Exception e) {
            log.error("[BscTrending] fetchNewTokens error: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    // ─── 构建 NewTokenRawData ─────────────────────────────

    private NewTokenRawData buildFromPair(JSONObject pair, ChainAnalysisProperties.BscTrending cfg, boolean hasBscKey) {
        JSONObject base = pair.getJSONObject("baseToken");
        if (base == null) return null;

        String baseSymbol = base.getString("symbol");
        if (baseSymbol != null && QUOTE_SYMBOLS.contains(baseSymbol.toUpperCase())) return null;

        String contractAddress = base.getString("address");
        if (contractAddress == null || contractAddress.isBlank()) return null;

        // 流动性过滤
        JSONObject liquidity = pair.getJSONObject("liquidity");
        BigDecimal liquidityUsd = liquidity != null ? liquidity.getBigDecimal("usd") : null;
        if (liquidityUsd == null || liquidityUsd.doubleValue() < cfg.getMinLiquidityUsd()) return null;

        // 市值过滤
        BigDecimal marketCapUsd = pair.getBigDecimal("marketCap");
        if (marketCapUsd != null) {
            double mcap = marketCapUsd.doubleValue();
            if (mcap < cfg.getMinMarketCapUsd() || mcap > cfg.getMaxMarketCapUsd()) return null;
        }

        // 换手率过滤（volume24h / liquidity）
        JSONObject volume = pair.getJSONObject("volume");
        BigDecimal volume24h = volume != null ? volume.getBigDecimal("h24") : null;
        if (volume24h != null && liquidityUsd.doubleValue() > 0) {
            double ratio = volume24h.doubleValue() / liquidityUsd.doubleValue();
            if (ratio < cfg.getMinVolumeLiquidityRatio()) return null;
        }

        // 1h 价格涨幅过滤
        JSONObject priceChange = pair.getJSONObject("priceChange");
        BigDecimal priceChange1h = priceChange != null ? priceChange.getBigDecimal("h1") : null;
        if (priceChange1h != null && priceChange1h.doubleValue() < cfg.getMinPriceChange1hPct()) return null;

        BigDecimal priceChange24h = priceChange != null ? priceChange.getBigDecimal("h24") : null;
        BigDecimal priceUsd = parseBigDecimal(pair.getString("priceUsd"));
        BigDecimal buyPressure = calcBuyPressure(pair);

        // 交易数量
        int txCount1h = 0;
        JSONObject txns = pair.getJSONObject("txns");
        if (txns != null) {
            JSONObject h1 = txns.getJSONObject("h1");
            if (h1 != null) txCount1h = h1.getIntValue("buys") + h1.getIntValue("sells");
        }

        long pairCreatedAt = pair.getLongValue("pairCreatedAt");
        int ageMinutes = pairCreatedAt > 0 ? (int) ((System.currentTimeMillis() - pairCreatedAt) / 60_000) : -1;

        // 可选 BSCScan 链上数据
        int holderCount = 0;
        boolean verified = false;
        if (hasBscKey) {
            holderCount = bscScanClient.getHolderCount(contractAddress);
            verified    = bscScanClient.isContractVerified(contractAddress);
        }

        return NewTokenRawData.builder()
                .chain("BNB")
                .contractAddress(contractAddress)
                .symbol(baseSymbol)
                .name(base.getString("name"))
                .pairAddress(pair.getString("pairAddress"))
                .listingTimeMs(pairCreatedAt > 0 ? pairCreatedAt : null)
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
                .bondingCurveProgress(null)
                .replyCount(null)
                .launchpadName(null)
                .dataSource("bnb_trending")
                .build();
    }

    // ─── 工具方法 ──────────────────────────────────────────

    private BigDecimal calcBuyPressure(JSONObject pair) {
        try {
            JSONObject txns = pair.getJSONObject("txns");
            if (txns == null) return BigDecimal.valueOf(0.5);
            JSONObject h1 = txns.getJSONObject("h1");
            if (h1 == null) return BigDecimal.valueOf(0.5);
            double buys  = h1.getDoubleValue("buys");
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
}

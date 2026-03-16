package com.vertex.service.chain.source.bnb;

import com.alibaba.fastjson2.JSONObject;
import com.vertex.api.chain.IChainSourceConfigService;
import com.vertex.model.entity.chain.ChainSourceConfig;
import com.vertex.service.chain.config.ChainAnalysisProperties;
import com.vertex.service.chain.source.ChainDataSource;
import com.vertex.service.chain.source.NewTokenRawData;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Binance Alpha 山寨币潜力筛选数据源
 * <p>
 * Binance Alpha 是币安旗下的早期项目展示平台，代币经过官方初步审核，
 * 具备较高的关注度和潜在上所预期。本数据源以"潜力筛选模式"运行：
 * <ul>
 *   <li>不限制代币上线时间（非新币发现逻辑）</li>
 *   <li>已存在于数据库的代币会刷新指标快照和评分</li>
 *   <li>适配 DEX 二级市场数据（流动性、成交量等）</li>
 * </ul>
 * <p>
 * 启用配置：{@code vertex.chain.binance-alpha.enabled=true}
 */
@Slf4j
@Component
public class BinanceAlphaDataSource implements ChainDataSource {

    /** 跳过主流大盘稳定币符号 */
    private static final Set<String> SKIP_SYMBOLS = Set.of(
            "WBNB", "BNB", "BUSD", "USDT", "USDC", "DAI", "BTCB", "ETH", "CAKE", "WBTC"
    );

    private final ChainAnalysisProperties properties;
    private final IChainSourceConfigService sourceConfigService;
    private final BinanceAlphaClient alphaClient;
    private final DexScreenerClient dexScreenerClient;

    public BinanceAlphaDataSource(
            ChainAnalysisProperties properties,
            IChainSourceConfigService sourceConfigService,
            @Qualifier("chainOkHttpClient") OkHttpClient httpClient) {
        this.properties = properties;
        this.sourceConfigService = sourceConfigService;
        ChainAnalysisProperties.BinanceAlpha cfg = properties.getBinanceAlpha();
        this.alphaClient = new BinanceAlphaClient(httpClient, cfg.getApiUrl());
        this.dexScreenerClient = new DexScreenerClient(httpClient);
    }

    @Override
    public String chainCode() {
        return "BNB";
    }

    @Override
    public String sourceId() {
        return "bnb_alpha";
    }

    @Override
    public boolean isAvailable() {
        return true; // 公开 API，无需 Key
    }

    @Override
    public boolean isScreeningMode() {
        return true; // 潜力筛选模式：允许更新已存在代币的指标
    }

    @Override
    public List<NewTokenRawData> fetchNewTokens(int scanWindowMinutes) {
        // 从数据库读取运行时开关，优先使用数据库配置，回退到 YAML 默认值
        ChainSourceConfig dbCfg = sourceConfigService.getBySourceId("bnb_alpha");
        if (dbCfg != null && !Integer.valueOf(1).equals(dbCfg.getEnabled())) {
            log.debug("[BinanceAlpha] Disabled by runtime config, skipping scan");
            return Collections.emptyList();
        }
        ChainAnalysisProperties.BinanceAlpha cfg = properties.getBinanceAlpha();
        try {
            double minMcap = (dbCfg != null && dbCfg.getMinMarketCapUsd() != null) ? dbCfg.getMinMarketCapUsd() : cfg.getMinMarketCapUsd();
            double minLiq = (dbCfg != null && dbCfg.getMinLiquidityUsd() != null) ? dbCfg.getMinLiquidityUsd() : cfg.getMinLiquidityUsd();
            // 新 API 一次性返回全量代币，无需分页参数
            List<JSONObject> alphaTokens = alphaClient.fetchAllTokens();
            if (alphaTokens.isEmpty()) {
                log.info("[BinanceAlpha] No tokens returned from API");
                return Collections.emptyList();
            }

            log.info("[BinanceAlpha] Fetched {} Alpha tokens", alphaTokens.size());
            List<NewTokenRawData> result = new ArrayList<>();

            for (JSONObject token : alphaTokens) {
                try {
                    NewTokenRawData data = buildFromAlpha(token, minMcap, minLiq);
                    if (data != null) result.add(data);
                } catch (Exception e) {
                    log.warn("[BinanceAlpha] Failed to process token {}: {}", token.getString("symbol"), e.getMessage());
                }
            }

            log.info("[BinanceAlpha] Processed {} valid Alpha tokens", result.size());
            return result;
        } catch (Exception e) {
            log.error("[BinanceAlpha] fetchNewTokens error: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    // ─── 构建 NewTokenRawData ─────────────────────────────

    private NewTokenRawData buildFromAlpha(JSONObject token, double minMarketCapUsd, double minLiquidityUsd) {
        // 只处理 BSC 链代币（新 API 字段：chainName，旧字段：network，兼容两者）
        String chainName = firstNonNull(token.getString("chainName"), token.getString("network"));
        if (chainName != null && !chainName.equalsIgnoreCase("BSC") && !chainName.equalsIgnoreCase("BNB")) {
            return null;
        }

        String contractAddress = firstNonNull(
                token.getString("contractAddress"),
                token.getString("tokenContractAddress"),
                token.getString("address")
        );
        if (contractAddress == null || contractAddress.isBlank()) return null;

        // 新 API symbol 字段为小写，统一转大写比较
        String symbol = firstNonNull(token.getString("symbol"), token.getString("tokenSymbol"));
        if (symbol != null && SKIP_SYMBOLS.contains(symbol.toUpperCase())) return null;

        // 市值过滤
        double marketCapUsd = parseDouble(token, "marketCap", "market_cap", "marketCapUsd");
        if (marketCapUsd > 0 && marketCapUsd < minMarketCapUsd) return null;

        // 流动性过滤
        double liquidityUsd = parseDouble(token, "liquidity", "liquidityUsd");
        if (liquidityUsd > 0 && liquidityUsd < minLiquidityUsd) return null;

        // 尝试通过 DexScreener 补充更详细的市场数据
        NewTokenRawData enriched = enrichWithDexScreener(contractAddress, symbol, token, marketCapUsd, liquidityUsd);
        return enriched;
    }

    /**
     * 用 DexScreener 数据补全 Binance Alpha 代币信息
     */
    private NewTokenRawData enrichWithDexScreener(
            String contractAddress, String symbol, JSONObject alphaToken,
            double fallbackMarketCap, double fallbackLiquidity) {

        // 先尝试从 DexScreener 获取最新行情
        List<JSONObject> pairs = dexScreenerClient.fetchTokenPairs("bsc", contractAddress);

        // 选择流动性最大的主交易对
        JSONObject bestPair = pairs.stream()
                .filter(p -> {
                    JSONObject liq = p.getJSONObject("liquidity");
                    return liq != null && liq.getDoubleValue("usd") > 0;
                })
                .max((a, b) -> {
                    JSONObject la = a.getJSONObject("liquidity");
                    JSONObject lb = b.getJSONObject("liquidity");
                    return Double.compare(
                            la != null ? la.getDoubleValue("usd") : 0,
                            lb != null ? lb.getDoubleValue("usd") : 0);
                })
                .orElse(null);

        String name = firstNonNull(alphaToken.getString("name"), alphaToken.getString("tokenName"), symbol);
        long deployTime = alphaToken.getLongValue("listingTime");
        // 新 API 字段为 holders，兼容旧字段 holderCount
        int holderCount = alphaToken.getIntValue("holders");
        if (holderCount == 0) holderCount = alphaToken.getIntValue("holderCount");

        if (bestPair != null) {
            // DexScreener 数据质量更高
            JSONObject base = bestPair.getJSONObject("baseToken");
            if (base != null && symbol == null) symbol = base.getString("symbol");

            JSONObject liquidity = bestPair.getJSONObject("liquidity");
            JSONObject volume    = bestPair.getJSONObject("volume");
            JSONObject priceChange = bestPair.getJSONObject("priceChange");

            BigDecimal liquidityUsd  = liquidity != null ? liquidity.getBigDecimal("usd") : null;
            BigDecimal volume24h     = volume != null ? volume.getBigDecimal("h24") : null;
            BigDecimal priceChange1h = priceChange != null ? priceChange.getBigDecimal("h1") : null;
            BigDecimal priceChange24h = priceChange != null ? priceChange.getBigDecimal("h24") : null;
            BigDecimal marketCap     = bestPair.getBigDecimal("marketCap");
            BigDecimal priceUsd      = parseBigDecimal(bestPair.getString("priceUsd"));
            BigDecimal buyPressure   = calcBuyPressure(bestPair);

            // 交易数量（从 txns 补充 txCount1h）
            int txCount1h = 0;
            JSONObject txns = bestPair.getJSONObject("txns");
            if (txns != null) {
                JSONObject h1 = txns.getJSONObject("h1");
                if (h1 != null) txCount1h = h1.getIntValue("buys") + h1.getIntValue("sells");
            }

            long pairCreatedAt = bestPair.getLongValue("pairCreatedAt");
            int ageMinutes = pairCreatedAt > 0
                    ? (int) ((System.currentTimeMillis() - pairCreatedAt) / 60_000) : -1;

            return NewTokenRawData.builder()
                    .chain("BNB")
                    .contractAddress(contractAddress)
                    .symbol(symbol)
                    .name(name)
                    .pairAddress(bestPair.getString("pairAddress"))
                    .listingTimeMs(pairCreatedAt > 0 ? pairCreatedAt : (deployTime > 0 ? deployTime : null))
                    .holderCount(holderCount > 0 ? holderCount : null)
                    .txCount1h(txCount1h)
                    .lpAddCount(1)
                    .liquidityLocked(false)
                    .contractVerified(null)
                    .priceUsd(priceUsd)
                    .marketCapUsd(marketCap != null ? marketCap : bigDecimalOf(fallbackMarketCap))
                    .liquidityUsd(liquidityUsd)
                    .volume24hUsd(volume24h)
                    .priceChange1hPct(priceChange1h)
                    .priceChange24hPct(priceChange24h)
                    .buyPressure1h(buyPressure)
                    .ageMinutes(ageMinutes >= 0 ? ageMinutes : null)
                    .pumpFunListed(false)
                    .bondingCurveProgress(null) // 已上 DEX
                    .replyCount(null)
                    .launchpadName("binance_alpha")
                    .dataSource("bnb_alpha")
                    .build();
        } else {
            // DexScreener 无数据，降级使用 Alpha API 原始数据
            BigDecimal price = parseBigDecimal(firstNonNull(
                    alphaToken.getString("price"), alphaToken.getString("priceUsd")));
            // 新 API 字段为 percentChange24h，旧字段为 priceChange24h
            BigDecimal priceChange24h = firstBigDecimal(
                    alphaToken.getString("percentChange24h"), alphaToken.getString("priceChange24h"));
            BigDecimal volume24h = bigDecimalOf(parseDouble(alphaToken, "volume24h", "volume24hUsd"));

            return NewTokenRawData.builder()
                    .chain("BNB")
                    .contractAddress(contractAddress)
                    .symbol(symbol)
                    .name(name)
                    .listingTimeMs(deployTime > 0 ? deployTime : null)
                    .holderCount(holderCount > 0 ? holderCount : null)
                    .txCount1h(null)
                    .lpAddCount(0)
                    .liquidityLocked(false)
                    .contractVerified(null)
                    .priceUsd(price)
                    .marketCapUsd(bigDecimalOf(fallbackMarketCap))
                    .liquidityUsd(bigDecimalOf(fallbackLiquidity))
                    .volume24hUsd(volume24h)
                    .priceChange1hPct(null)
                    .priceChange24hPct(priceChange24h)
                    .buyPressure1h(null)
                    .ageMinutes(null)
                    .pumpFunListed(false)
                    .bondingCurveProgress(null)
                    .replyCount(null)
                    .launchpadName("binance_alpha")
                    .dataSource("bnb_alpha")
                    .build();
        }
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

    private double parseDouble(JSONObject obj, String... keys) {
        for (String key : keys) {
            Double val = obj.getDouble(key);
            if (val != null && val > 0) return val;
            String str = obj.getString(key);
            if (str != null && !str.isBlank()) {
                try { double d = Double.parseDouble(str); if (d > 0) return d; } catch (Exception ignored) {}
            }
        }
        return 0;
    }

    private BigDecimal bigDecimalOf(double val) {
        return val > 0 ? BigDecimal.valueOf(val) : null;
    }

    private BigDecimal parseBigDecimal(String val) {
        if (val == null || val.isBlank()) return null;
        try { return new BigDecimal(val); } catch (Exception e) { return null; }
    }

    private BigDecimal firstBigDecimal(String... vals) {
        for (String v : vals) {
            BigDecimal bd = parseBigDecimal(v);
            if (bd != null) return bd;
        }
        return null;
    }

    private String firstNonNull(String... vals) {
        for (String v : vals) if (v != null && !v.isBlank()) return v;
        return null;
    }
}

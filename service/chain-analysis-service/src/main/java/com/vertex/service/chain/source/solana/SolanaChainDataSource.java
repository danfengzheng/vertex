package com.vertex.service.chain.source.solana;

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

/**
 * Solana 一级市场数据源
 * <p>
 * 专注于 Pump.fun 上 <b>尚未毕业</b>（{@code complete=false}）的代币，
 * 即 bonding curve 阶段的一级市场代币：
 * <ul>
 *   <li>代币尚未在 Raydium / Jupiter 等 DEX 上市</li>
 *   <li>仍在 Pump.fun 内部 bonding curve 上买卖</li>
 *   <li>当 bonding curve 积累约 85 SOL 后自动毕业 → 转至 DEX（二级市场）</li>
 * </ul>
 * <p>
 * 数据来源：
 * <ul>
 *   <li>Pump.fun Frontend API — 代币列表、bonding curve 进度、社区热度（免费，无需 Key）</li>
 *   <li>Helius RPC — 持有者数量（需 Key，可选，缺失时自动降级）</li>
 * </ul>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "vertex.chain.solana", name = "enabled", havingValue = "true", matchIfMissing = true)
public class SolanaChainDataSource implements ChainDataSource {

    // Pump.fun bonding curve 参数（lamports = SOL * 1e9）
    private static final long INITIAL_VIRTUAL_SOL  = 30_000_000_000L;   // 30 SOL 起始虚拟储备
    private static final long GRADUATION_VIRTUAL_SOL = 85_000_000_000L; // ~85 SOL 触发毕业

    private final ChainAnalysisProperties properties;
    private final HeliusClient heliusClient;
    private final PumpFunClient pumpFunClient;

    public SolanaChainDataSource(
            ChainAnalysisProperties properties,
            @Qualifier("chainOkHttpClient") OkHttpClient httpClient) {
        this.properties = properties;
        ChainAnalysisProperties.Solana cfg = properties.getSolana();
        this.heliusClient = new HeliusClient(httpClient, cfg.getHeliusRpcUrl(), cfg.getHeliusApiKey());
        this.pumpFunClient = new PumpFunClient(httpClient, cfg.getPumpfunApiUrl());
    }

    @Override
    public String chainCode() {
        return "SOL";
    }

    @Override
    public boolean isAvailable() {
        return true; // Pump.fun 无需 Key，始终可用
    }

    @Override
    public List<NewTokenRawData> fetchNewTokens(int scanWindowMinutes) {
        try {
            long windowMs = (long) scanWindowMinutes * 60_000;
            long since    = System.currentTimeMillis() - windowMs;
            double minMarketCapUsd = properties.getSolana().getMinLiquidityUsd(); // 复用配置作为市值下限

            // 1. 获取 Pump.fun 最新代币（按创建时间倒序，包含 bonding curve 数据）
            List<JSONObject> tokens = pumpFunClient.fetchLatestTokens(50);
            if (tokens.isEmpty()) {
                log.debug("[SOL] Pump.fun returned no tokens");
                return Collections.emptyList();
            }

            List<NewTokenRawData> result = new ArrayList<>();
            for (JSONObject token : tokens) {
                try {
                    // 1a. 只处理时间窗口内的代币
                    long createdTs = token.getLongValue("created_timestamp");
                    if (createdTs > 0 && createdTs < since) continue;

                    // 1b. 只处理 【未毕业】代币（一级市场）
                    boolean graduated = Boolean.TRUE.equals(token.getBoolean("complete"));
                    if (graduated) continue;

                    NewTokenRawData data = buildFromPumpFun(token, minMarketCapUsd);
                    if (data != null) result.add(data);
                } catch (Exception e) {
                    log.warn("[SOL] Failed to process token {}: {}", token.getString("mint"), e.getMessage());
                }
            }
            log.info("[SOL] Primary market scan: {} new tokens (non-graduated, from {} pump.fun entries)",
                    result.size(), tokens.size());
            return result;
        } catch (Exception e) {
            log.error("[SOL] fetchNewTokens error: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    // ─── 内部构建 ──────────────────────────────────────────

    private NewTokenRawData buildFromPumpFun(JSONObject token, double minMarketCapUsd) {
        String mintAddress = token.getString("mint");
        if (mintAddress == null) return null;

        // 市值过滤（代替 minLiquidity，bonding curve 阶段无 DEX 流动性）
        double usdMcRaw = token.getDoubleValue("usd_market_cap");
        if (usdMcRaw < minMarketCapUsd) return null;
        BigDecimal usdMarketCap = BigDecimal.valueOf(usdMcRaw);

        // ── Bonding curve 进度 ──────────────────────────────
        double bondingPct = calcBondingCurveProgress(token);

        // ── 社区热度 ───────────────────────────────────────
        int replyCount = token.getIntValue("reply_count");

        // ── 时间 ───────────────────────────────────────────
        long createdTs  = token.getLongValue("created_timestamp");
        int ageMinutes  = createdTs > 0 ? (int) ((System.currentTimeMillis() - createdTs) / 60_000) : -1;

        // ── 持有者数（Helius，可选）─────────────────────────
        boolean hasHelius  = hasHeliusKey();
        int holderCount    = hasHelius ? heliusClient.getTokenHolderCount(mintAddress) : 0;
        int decimals       = hasHelius ? heliusClient.getDecimals(mintAddress) : 6;

        // ── 价格（pump.fun 内部 bonding curve 价格）──────────
        // pump.fun 的 `market_cap` 是 SOL 计价，`usd_market_cap` 是 USD 计价
        // 从市值和总供应量推算价格（近似）
        BigDecimal priceUsd = estimatePriceFromMarketCap(token);

        return NewTokenRawData.builder()
                .chain("SOL")
                .contractAddress(mintAddress)
                .symbol(token.getString("symbol"))
                .name(token.getString("name"))
                .decimals(decimals)
                .deployerAddress(token.getString("creator"))
                .pairAddress(null)          // 一级市场：无 DEX 交易对
                .listingTimeMs(createdTs > 0 ? createdTs : null)
                // ── 链上 ──
                .holderCount(holderCount > 0 ? holderCount : null)
                .txCount1h(null)            // bonding curve 内部无法直接获取 tx 数
                .lpAddCount(0)              // 未毕业：尚无 LP
                .liquidityLocked(false)     // 未毕业：无锁仓
                .contractVerified(true)     // Solana SPL Token 无需传统审计
                // ── 市场（bonding curve 阶段无 DEX 数据）──
                .priceUsd(priceUsd)
                .marketCapUsd(usdMarketCap)
                .liquidityUsd(null)         // 无 DEX 流动性
                .volume24hUsd(null)         // 无 DEX 成交量
                .priceChange1hPct(null)
                .priceChange24hPct(null)
                .buyPressure1h(null)
                // ── 代币经济 ──
                .top10HolderPct(null)
                .deployerHoldingPct(null)
                .lpPoolPct(null)
                // ── 新颖度 ──
                .ageMinutes(ageMinutes >= 0 ? ageMinutes : null)
                .pumpFunListed(true)
                // ── 一级市场专属 ──
                .bondingCurveProgress(bondingPct)
                .replyCount(replyCount)
                .launchpadName("pump.fun")
                .build();
    }

    /**
     * 从 virtual_sol_reserves 计算 bonding curve 填充进度（0-100%）。
     * <p>
     * Pump.fun bonding curve 参数：
     * <ul>
     *   <li>初始虚拟 SOL 储备 ≈ 30 SOL（30_000_000_000 lamports）</li>
     *   <li>达到 ≈ 85 SOL 时触发毕业</li>
     * </ul>
     * 进度越高（接近 100%）表示越快即将毕业上 DEX，机会信号越强。
     */
    private double calcBondingCurveProgress(JSONObject token) {
        // pump.fun API 有时直接返回 bonding_curve_progress 字段（0-100）
        Double direct = token.getDouble("bonding_curve_progress");
        if (direct != null && direct > 0) return Math.min(direct, 100.0);

        // 否则从 virtual_sol_reserves 计算
        long virtualSol = token.getLongValue("virtual_sol_reserves");
        if (virtualSol <= INITIAL_VIRTUAL_SOL) return 0.0;
        double pct = (double)(virtualSol - INITIAL_VIRTUAL_SOL)
                   / (GRADUATION_VIRTUAL_SOL - INITIAL_VIRTUAL_SOL) * 100.0;
        return Math.min(Math.max(pct, 0.0), 100.0);
    }

    /** 从市值 ÷ 总供应量估算单价（pump.fun 总发行量约为 1,000,000,000） */
    private BigDecimal estimatePriceFromMarketCap(JSONObject token) {
        try {
            double usdMc = token.getDoubleValue("usd_market_cap");
            if (usdMc <= 0) return null;
            // pump.fun 代币总供应量固定约 1B（1_000_000_000）
            double totalSupply = 1_000_000_000.0;
            return BigDecimal.valueOf(usdMc / totalSupply);
        } catch (Exception e) {
            return null;
        }
    }

    private boolean hasHeliusKey() {
        String key = properties.getSolana().getHeliusApiKey();
        return key != null && !key.isBlank();
    }
}

package com.vertex.service.chain.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * 多链新币分析模块配置
 */
@Data
@ConfigurationProperties(prefix = "vertex.chain")
public class ChainAnalysisProperties {

    /** 是否启用链上分析模块 */
    private boolean enabled = true;

    /** 扫描间隔基础秒数（固定延迟，不含随机抖动） */
    private int scanIntervalSeconds = 300;

    /** 随机额外延迟最大秒数（0 ~ maxJitterSeconds 均匀随机），与 scanIntervalSeconds 叠加实现 5~15 min 随机 */
    private int maxJitterSeconds = 600;

    /** 最低评分阈值（低于此分数不写入数据库） */
    private int minScoreToSave = 20;

    /** BNB Chain 配置 */
    private Bnb bnb = new Bnb();

    /** Solana 配置 */
    private Solana solana = new Solana();

    /** Telegram 告警配置（复用现有 Bot Token） */
    private Telegram telegram = new Telegram();

    // ─── BNB Chain ────────────────────────────────────────

    @Data
    public static class Bnb {
        /** 是否启用 BNB Chain 数据源 */
        private boolean enabled = true;
        /** BSCScan API Key */
        private String bscscanApiKey = "";
        /** BSCScan API 地址 */
        private String bscscanApiUrl = "https://api.bscscan.com/api";
        /** PancakeSwap V3 Subgraph API */
        private String pancakeswapGraphUrl = "https://api.thegraph.com/subgraphs/name/pancakeswap/exchange-v3-bsc";
        /** 新币扫描时间窗口（分钟），扫描最近 N 分钟上线的代币 */
        private int scanWindowMinutes = 30;
        /** 最小流动性 USD 过滤阈值，低于此值不处理 */
        private double minLiquidityUsd = 1000.0;
    }

    // ─── Solana ───────────────────────────────────────────

    @Data
    public static class Solana {
        /** 是否启用 Solana 数据源 */
        private boolean enabled = true;
        /** Helius API Key */
        private String heliusApiKey = "";
        /** Helius RPC URL（含 API Key） */
        private String heliusRpcUrl = "https://mainnet.helius-rpc.com";
        /** Jupiter 价格/代币 API */
        private String jupiterApiUrl = "https://price.jup.ag/v4";
        /** Pump.fun 新币列表 API */
        private String pumpfunApiUrl = "https://frontend-api.pump.fun";
        /** 新币扫描时间窗口（分钟） */
        private int scanWindowMinutes = 30;
        /** 最小流动性 USD 过滤阈值 */
        private double minLiquidityUsd = 500.0;
    }

    // ─── Telegram ─────────────────────────────────────────

    @Data
    public static class Telegram {
        /** 是否启用 Telegram 告警 */
        private boolean enabled = false;
        /** Bot Token */
        private String botToken = "";
        /** Chat ID（用户或群组） */
        private String chatId = "";
        /** API 地址（支持代理） */
        private String apiUrl = "https://api.telegram.org";
    }
}

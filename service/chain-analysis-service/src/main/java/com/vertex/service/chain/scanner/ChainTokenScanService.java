package com.vertex.service.chain.scanner;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.vertex.model.entity.chain.ChainToken;
import com.vertex.model.entity.chain.ChainTokenMetrics;
import com.vertex.service.chain.alert.ChainAlertService;
import com.vertex.service.chain.mapper.ChainTokenMapper;
import com.vertex.service.chain.mapper.ChainTokenMetricsMapper;
import com.vertex.service.chain.scorer.CompositeTokenScorer;
import com.vertex.service.chain.scorer.TokenScoreResult;
import com.vertex.service.chain.source.ChainDataSource;
import com.vertex.service.chain.source.NewTokenRawData;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * 链上代币扫描处理服务
 * <p>
 * 对每条链执行以下幂等流程：
 * <ol>
 *   <li>从 {@link ChainDataSource} 获取原始新代币数据</li>
 *   <li>按 {@code (chain, contractAddress)} 去重，已存在则跳过</li>
 *   <li>调用 {@link CompositeTokenScorer} 计算各维度评分</li>
 *   <li>将代币和指标快照写入数据库</li>
 *   <li>通过 {@link ChainAlertService} 执行告警规则匹配</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChainTokenScanService {

    private final List<ChainDataSource> dataSources;
    private final CompositeTokenScorer scorer;
    private final ChainTokenMapper chainTokenMapper;
    private final ChainTokenMetricsMapper metricsMapper;
    private final ChainAlertService alertService;

    /**
     * 执行全链扫描（由 {@link NewTokenScanner} 定时调用）
     *
     * @param scanWindowMinutes 扫描时间窗口（分钟）
     */
    public void scanAll(int scanWindowMinutes) {
        for (ChainDataSource source : dataSources) {
            if (!source.isAvailable()) {
                log.debug("[Scan] {} data source not available, skipping", source.chainCode());
                continue;
            }
            try {
                scanChain(source, scanWindowMinutes);
            } catch (Exception e) {
                log.error("[Scan] Error scanning chain {}: {}", source.chainCode(), e.getMessage(), e);
            }
        }
    }

    /**
     * 对单条链执行扫描（可供 Controller 手动触发）
     *
     * @param chainCode         链标识
     * @param scanWindowMinutes 扫描时间窗口（分钟）
     */
    public void scanChain(String chainCode, int scanWindowMinutes) {
        dataSources.stream()
                .filter(s -> s.chainCode().equalsIgnoreCase(chainCode))
                .findFirst()
                .ifPresentOrElse(
                        s -> scanChain(s, scanWindowMinutes),
                        () -> log.warn("[Scan] No data source found for chain: {}", chainCode)
                );
    }

    // ─── 内部方法 ──────────────────────────────────────────

    private void scanChain(ChainDataSource source, int scanWindowMinutes) {
        String chain = source.chainCode();
        log.info("[Scan] Starting {} scan, window={}min", chain, scanWindowMinutes);

        List<NewTokenRawData> tokens = source.fetchNewTokens(scanWindowMinutes);
        log.info("[Scan] {} returned {} new token(s)", chain, tokens.size());

        int saved = 0, skipped = 0;
        for (NewTokenRawData raw : tokens) {
            try {
                boolean processed = processToken(raw);
                if (processed) saved++; else skipped++;
            } catch (Exception e) {
                log.warn("[Scan] Failed to process token {} {}: {}",
                        chain, raw.getContractAddress(), e.getMessage());
            }
        }
        log.info("[Scan] {} scan done: saved={}, skipped(duplicate)={}", chain, saved, skipped);
    }

    /**
     * 处理单个代币：幂等写入 → 评分 → 告警
     *
     * @return true=新记录已保存，false=已存在跳过
     */
    @Transactional
    public boolean processToken(NewTokenRawData raw) {
        // 1. 幂等检查
        Long existing = chainTokenMapper.selectCount(
                new LambdaQueryWrapper<ChainToken>()
                        .eq(ChainToken::getChain, raw.getChain())
                        .eq(ChainToken::getContractAddress, raw.getContractAddress()));
        if (existing > 0) return false;

        // 2. 评分
        TokenScoreResult scoreResult = scorer.score(raw);

        // 3. 保存代币基础信息
        ChainToken token = new ChainToken();
        token.setChain(raw.getChain());
        token.setContractAddress(raw.getContractAddress());
        token.setSymbol(raw.getSymbol());
        token.setName(raw.getName());
        token.setDecimals(raw.getDecimals());
        token.setDeployerAddress(raw.getDeployerAddress());
        token.setDeployTime(raw.getListingTimeMs());
        token.setPairAddress(raw.getPairAddress());
        token.setScore(scoreResult.getTotalScore());
        token.setScoreOnchain(scoreResult.getScoreOnChain());
        token.setScoreMarket(scoreResult.getScoreMarket());
        token.setScoreTokenomics(scoreResult.getScoreTokenomics());
        token.setScoreNovelty(scoreResult.getScoreNovelty());
        token.setStatus("SCORED");
        token.setAlerted(0);
        chainTokenMapper.insert(token);

        // 4. 保存指标快照
        ChainTokenMetrics metrics = buildMetrics(token.getId(), raw);
        metricsMapper.insert(metrics);

        // 5. 告警检查
        alertService.checkAndAlert(token, metrics);

        return true;
    }

    // ─── 构建 Metrics 实体 ─────────────────────────────────

    private ChainTokenMetrics buildMetrics(Long tokenId, NewTokenRawData raw) {
        ChainTokenMetrics m = new ChainTokenMetrics();
        m.setTokenId(tokenId);
        m.setSnapshotTime(System.currentTimeMillis());
        m.setHolderCount(raw.getHolderCount());
        m.setTxCount1h(raw.getTxCount1h());
        m.setLpAddCount(raw.getLpAddCount());
        m.setLiquidityLocked(raw.getLiquidityLocked() != null && raw.getLiquidityLocked() ? 1 : 0);
        m.setContractVerified(raw.getContractVerified() != null && raw.getContractVerified() ? 1 : 0);
        m.setPriceUsd(raw.getPriceUsd());
        m.setMarketCapUsd(raw.getMarketCapUsd());
        m.setLiquidityUsd(raw.getLiquidityUsd());
        m.setVolume24hUsd(raw.getVolume24hUsd());
        m.setPriceChange1hPct(raw.getPriceChange1hPct());
        m.setPriceChange24hPct(raw.getPriceChange24hPct());
        m.setBuyPressure1h(raw.getBuyPressure1h() != null
                ? raw.getBuyPressure1h().multiply(BigDecimal.valueOf(100)) // 转为百分比
                : null);
        m.setTop10HolderPct(raw.getTop10HolderPct());
        m.setDeployerHoldingPct(raw.getDeployerHoldingPct());
        m.setLpPoolPct(raw.getLpPoolPct());
        m.setAgeMinutes(raw.getAgeMinutes());
        m.setPumpFunListed(raw.getPumpFunListed() != null && raw.getPumpFunListed() ? 1 : 0);
        // 一级市场专属字段
        m.setBondingCurveProgress(raw.getBondingCurveProgress() != null
                ? BigDecimal.valueOf(raw.getBondingCurveProgress()) : null);
        m.setReplyCount(raw.getReplyCount());
        m.setLaunchpadName(raw.getLaunchpadName());
        return m;
    }
}

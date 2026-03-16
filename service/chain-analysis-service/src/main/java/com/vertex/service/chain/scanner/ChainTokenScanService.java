package com.vertex.service.chain.scanner;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
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
import java.util.Set;

/**
 * 链上代币扫描处理服务
 * <p>
 * 对每条链执行以下幂等流程（新币发现模式）：
 * <ol>
 *   <li>从 {@link ChainDataSource} 获取原始代币数据</li>
 *   <li>按 {@code (chain, contractAddress)} 去重，已存在则跳过</li>
 *   <li>调用 {@link CompositeTokenScorer} 计算各维度评分</li>
 *   <li>将代币和指标快照写入数据库</li>
 *   <li>通过 {@link ChainAlertService} 执行告警规则匹配</li>
 * </ol>
 * <p>
 * 潜力筛选模式（screening mode，如 Binance Alpha / BSC Trending）：
 * 已存在的代币会更新 {@code dataSource}、重新评分并追加新的指标快照，
 * 用于持续跟踪代币的动态变化。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChainTokenScanService {

    /** 潜力筛选数据源标识集合 */
    private static final Set<String> SCREENING_SOURCES = Set.of("bnb_alpha", "bnb_trending");

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
                log.debug("[Scan] {} data source not available, skipping", source.sourceId());
                continue;
            }
            try {
                scanChain(source, scanWindowMinutes);
            } catch (Exception e) {
                log.error("[Scan] Error scanning source {}: {}", source.sourceId(), e.getMessage(), e);
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
        String sid = source.sourceId();
        boolean screening = source.isScreeningMode();
        log.info("[Scan] Starting {} scan (mode={}), window={}min", sid,
                screening ? "screening" : "new-token", scanWindowMinutes);

        List<NewTokenRawData> tokens = source.fetchNewTokens(scanWindowMinutes);
        log.info("[Scan] {} returned {} token(s)", sid, tokens.size());

        int saved = 0, updated = 0, skipped = 0;
        for (NewTokenRawData raw : tokens) {
            try {
                int result = processToken(raw);
                if (result > 0) saved++;
                else if (result < 0) updated++;
                else skipped++;
            } catch (Exception e) {
                log.warn("[Scan] Failed to process token {} {}: {}",
                        sid, raw.getContractAddress(), e.getMessage());
            }
        }
        log.info("[Scan] {} scan done: inserted={}, updated={}, skipped={}", sid, saved, updated, skipped);
    }

    /**
     * 处理单个代币：
     * <ul>
     *   <li>新币模式：幂等插入，返回 1（新插入）或 0（已存在跳过）</li>
     *   <li>筛选模式：新则插入返回 1；已存在则刷新指标+评分返回 -1</li>
     * </ul>
     */
    @Transactional
    public int processToken(NewTokenRawData raw) {
        boolean isScreening = raw.getDataSource() != null
                && SCREENING_SOURCES.contains(raw.getDataSource());

        // 查已存在记录
        ChainToken existing = chainTokenMapper.selectOne(
                new LambdaQueryWrapper<ChainToken>()
                        .eq(ChainToken::getChain, raw.getChain())
                        .eq(ChainToken::getContractAddress, raw.getContractAddress())
                        .eq(ChainToken::getDeleted, 0));

        if (existing != null && !isScreening) {
            return 0; // 新币模式：已存在则跳过
        }

        // 评分
        TokenScoreResult scoreResult = scorer.score(raw);

        if (existing != null) {
            // 筛选模式：更新已存在代币的评分和数据来源
            refreshExistingToken(existing, raw, scoreResult);
            return -1;
        }

        // 新代币：插入
        ChainToken token = buildNewToken(raw, scoreResult);
        chainTokenMapper.insert(token);

        ChainTokenMetrics metrics = buildMetrics(token.getId(), raw);
        metricsMapper.insert(metrics);

        alertService.checkAndAlert(token, metrics);
        return 1;
    }

    // ─── 筛选模式：刷新已存在代币 ────────────────────────────

    private void refreshExistingToken(ChainToken token, NewTokenRawData raw, TokenScoreResult score) {
        // 更新评分和数据来源
        chainTokenMapper.update(null,
                new LambdaUpdateWrapper<ChainToken>()
                        .eq(ChainToken::getId, token.getId())
                        .set(ChainToken::getScore, score.getTotalScore())
                        .set(ChainToken::getScoreOnchain, score.getScoreOnChain())
                        .set(ChainToken::getScoreMarket, score.getScoreMarket())
                        .set(ChainToken::getScoreTokenomics, score.getScoreTokenomics())
                        .set(ChainToken::getScoreNovelty, score.getScoreNovelty())
                        .set(ChainToken::getDataSource, raw.getDataSource())
        );

        // 追加新指标快照
        ChainTokenMetrics metrics = buildMetrics(token.getId(), raw);
        metricsMapper.insert(metrics);

        // 满足告警条件时重新触发（仅在未曾告警过的代币上）
        if (token.getAlerted() == null || token.getAlerted() == 0) {
            token.setScore(score.getTotalScore());
            alertService.checkAndAlert(token, metrics);
        }
    }

    // ─── 构建新代币实体 ────────────────────────────────────

    private ChainToken buildNewToken(NewTokenRawData raw, TokenScoreResult score) {
        ChainToken token = new ChainToken();
        token.setChain(raw.getChain());
        token.setContractAddress(raw.getContractAddress());
        token.setSymbol(raw.getSymbol());
        token.setName(raw.getName());
        token.setDecimals(raw.getDecimals());
        token.setDeployerAddress(raw.getDeployerAddress());
        token.setDeployTime(raw.getListingTimeMs());
        token.setPairAddress(raw.getPairAddress());
        token.setScore(score.getTotalScore());
        token.setScoreOnchain(score.getScoreOnChain());
        token.setScoreMarket(score.getScoreMarket());
        token.setScoreTokenomics(score.getScoreTokenomics());
        token.setScoreNovelty(score.getScoreNovelty());
        token.setDataSource(raw.getDataSource());
        token.setStatus("SCORED");
        token.setAlerted(0);
        return token;
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
                ? raw.getBuyPressure1h().multiply(BigDecimal.valueOf(100))
                : null);
        m.setTop10HolderPct(raw.getTop10HolderPct());
        m.setDeployerHoldingPct(raw.getDeployerHoldingPct());
        m.setLpPoolPct(raw.getLpPoolPct());
        m.setAgeMinutes(raw.getAgeMinutes());
        m.setPumpFunListed(raw.getPumpFunListed() != null && raw.getPumpFunListed() ? 1 : 0);
        m.setBondingCurveProgress(raw.getBondingCurveProgress() != null
                ? BigDecimal.valueOf(raw.getBondingCurveProgress()) : null);
        m.setReplyCount(raw.getReplyCount());
        m.setLaunchpadName(raw.getLaunchpadName());
        return m;
    }
}

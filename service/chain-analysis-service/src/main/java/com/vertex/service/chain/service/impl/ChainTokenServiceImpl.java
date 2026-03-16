package com.vertex.service.chain.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.vertex.api.chain.IChainTokenService;
import com.vertex.common.core.page.PageResult;
import com.vertex.model.dto.chain.ChainTokenQueryDTO;
import com.vertex.model.entity.chain.ChainToken;
import com.vertex.model.entity.chain.ChainTokenMetrics;
import com.vertex.model.vo.chain.ChainTokenDetailVO;
import com.vertex.model.vo.chain.ChainTokenVO;
import com.vertex.service.chain.mapper.ChainTokenMapper;
import com.vertex.service.chain.mapper.ChainTokenMetricsMapper;
import com.vertex.service.chain.scanner.ChainTokenScanService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 链上新币分析服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChainTokenServiceImpl implements IChainTokenService {

    private final ChainTokenMapper chainTokenMapper;
    private final ChainTokenMetricsMapper metricsMapper;
    private final ChainTokenScanService scanService;

    @Override
    public PageResult<ChainTokenVO> page(ChainTokenQueryDTO query) {
        LambdaQueryWrapper<ChainToken> wrapper = buildQueryWrapper(query);
        Page<ChainToken> page = chainTokenMapper.selectPage(
                new Page<>(query.getPageNum(), query.getPageSize()), wrapper);

        List<ChainTokenVO> records = page.getRecords().stream()
                .map(this::toVO)
                .collect(Collectors.toList());
        return PageResult.of(page.getTotal(), records);
    }

    @Override
    public ChainTokenDetailVO getById(Long id) {
        ChainToken token = chainTokenMapper.selectById(id);
        if (token == null) return null;

        // 查最新指标快照
        ChainTokenMetrics metrics = metricsMapper.selectOne(
                new LambdaQueryWrapper<ChainTokenMetrics>()
                        .eq(ChainTokenMetrics::getTokenId, id)
                        .orderByDesc(ChainTokenMetrics::getSnapshotTime)
                        .last("LIMIT 1"));

        return toDetailVO(token, metrics);
    }

    @Override
    @Async
    public void triggerScan(String chain) {
        log.info("[ChainToken] Manual scan triggered for chain: {}", chain);
        if (chain == null || chain.isBlank() || "ALL".equalsIgnoreCase(chain)) {
            scanService.scanAll(30);
        } else {
            scanService.scanChain(chain, 30);
        }
    }

    // ─── 转换方法 ──────────────────────────────────────────

    private ChainTokenVO toVO(ChainToken token) {
        // 查最新指标（列表页只需要几个市场字段）
        ChainTokenMetrics metrics = metricsMapper.selectOne(
                new LambdaQueryWrapper<ChainTokenMetrics>()
                        .eq(ChainTokenMetrics::getTokenId, token.getId())
                        .orderByDesc(ChainTokenMetrics::getSnapshotTime)
                        .last("LIMIT 1"));

        return ChainTokenVO.builder()
                .id(token.getId())
                .chain(token.getChain())
                .contractAddress(token.getContractAddress())
                .symbol(token.getSymbol())
                .name(token.getName())
                .deployTime(token.getDeployTime())
                .score(token.getScore())
                .scoreOnchain(token.getScoreOnchain())
                .scoreMarket(token.getScoreMarket())
                .scoreTokenomics(token.getScoreTokenomics())
                .scoreNovelty(token.getScoreNovelty())
                .status(token.getStatus())
                .alerted(token.getAlerted())
                .priceUsd(metrics != null ? metrics.getPriceUsd() : null)
                .marketCapUsd(metrics != null ? metrics.getMarketCapUsd() : null)
                .liquidityUsd(metrics != null ? metrics.getLiquidityUsd() : null)
                .volume24hUsd(metrics != null ? metrics.getVolume24hUsd() : null)
                .holderCount(metrics != null ? metrics.getHolderCount() : null)
                .createTime(token.getCreateTime())
                .dataSource(token.getDataSource())
                .build();
    }

    private ChainTokenDetailVO toDetailVO(ChainToken token, ChainTokenMetrics m) {
        ChainTokenDetailVO vo = new ChainTokenDetailVO();
        // 基础字段（复用 ChainTokenVO builder 的字段）
        vo.setId(token.getId());
        vo.setChain(token.getChain());
        vo.setContractAddress(token.getContractAddress());
        vo.setSymbol(token.getSymbol());
        vo.setName(token.getName());
        vo.setDeployTime(token.getDeployTime());
        vo.setScore(token.getScore());
        vo.setScoreOnchain(token.getScoreOnchain());
        vo.setScoreMarket(token.getScoreMarket());
        vo.setScoreTokenomics(token.getScoreTokenomics());
        vo.setScoreNovelty(token.getScoreNovelty());
        vo.setStatus(token.getStatus());
        vo.setAlerted(token.getAlerted());
        vo.setCreateTime(token.getCreateTime());
        vo.setDataSource(token.getDataSource());

        // 详情字段
        vo.setDecimals(token.getDecimals());
        vo.setTotalSupply(token.getTotalSupply());
        vo.setDeployerAddress(token.getDeployerAddress());
        vo.setDeployBlock(token.getDeployBlock());
        vo.setPairAddress(token.getPairAddress());
        vo.setQuoteToken(token.getQuoteToken());

        // 指标字段
        if (m != null) {
            vo.setPriceUsd(m.getPriceUsd());
            vo.setMarketCapUsd(m.getMarketCapUsd());
            vo.setLiquidityUsd(m.getLiquidityUsd());
            vo.setVolume24hUsd(m.getVolume24hUsd());
            vo.setHolderCount(m.getHolderCount());
            vo.setPriceChange1hPct(m.getPriceChange1hPct());
            vo.setPriceChange24hPct(m.getPriceChange24hPct());
            vo.setBuyPressure1h(m.getBuyPressure1h());
            vo.setTop10HolderPct(m.getTop10HolderPct());
            vo.setDeployerHoldingPct(m.getDeployerHoldingPct());
            vo.setLpPoolPct(m.getLpPoolPct());
            vo.setAgeMinutes(m.getAgeMinutes());
            vo.setPumpFunListed(m.getPumpFunListed());
            vo.setLiquidityLocked(m.getLiquidityLocked());
            vo.setContractVerified(m.getContractVerified());
            vo.setTxCount1h(m.getTxCount1h());
            vo.setLpAddCount(m.getLpAddCount());
            // 一级市场专属
            vo.setBondingCurveProgress(m.getBondingCurveProgress());
            vo.setReplyCount(m.getReplyCount());
            vo.setLaunchpadName(m.getLaunchpadName());
        }
        return vo;
    }

    private LambdaQueryWrapper<ChainToken> buildQueryWrapper(ChainTokenQueryDTO query) {
        return new LambdaQueryWrapper<ChainToken>()
                .eq(query.getChain() != null, ChainToken::getChain, query.getChain())
                .like(query.getSymbol() != null && !query.getSymbol().isBlank(),
                        ChainToken::getSymbol, query.getSymbol())
                .ge(query.getMinScore() != null, ChainToken::getScore, query.getMinScore())
                .eq(query.getStatus() != null, ChainToken::getStatus, query.getStatus())
                .ge(query.getDeployTimeFrom() != null, ChainToken::getDeployTime, query.getDeployTimeFrom())
                .le(query.getDeployTimeTo() != null, ChainToken::getDeployTime, query.getDeployTimeTo())
                .eq(query.getDataSource() != null && !query.getDataSource().isBlank(),
                        ChainToken::getDataSource, query.getDataSource())
                .eq(ChainToken::getDeleted, 0)
                .orderByDesc(ChainToken::getScore)
                .orderByDesc(ChainToken::getCreateTime);
    }
}

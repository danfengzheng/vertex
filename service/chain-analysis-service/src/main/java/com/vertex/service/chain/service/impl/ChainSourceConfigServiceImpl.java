package com.vertex.service.chain.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.vertex.api.chain.IChainSourceConfigService;
import com.vertex.common.core.exception.BizException;
import com.vertex.model.dto.chain.ChainSourceConfigUpdateDTO;
import com.vertex.model.entity.chain.ChainSourceConfig;
import com.vertex.model.vo.chain.ChainSourceConfigVO;
import com.vertex.service.chain.mapper.ChainSourceConfigMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 链上数据源配置服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChainSourceConfigServiceImpl implements IChainSourceConfigService {

    private final ChainSourceConfigMapper configMapper;

    @Override
    public List<ChainSourceConfigVO> listAll() {
        List<ChainSourceConfig> list = configMapper.selectList(
                new LambdaQueryWrapper<ChainSourceConfig>()
                        .eq(ChainSourceConfig::getDeleted, 0)
                        .orderByAsc(ChainSourceConfig::getId));
        return list.stream().map(this::toVO).collect(Collectors.toList());
    }

    @Override
    public void update(ChainSourceConfigUpdateDTO dto) {
        ChainSourceConfig cfg = configMapper.selectById(dto.getId());
        if (cfg == null || Integer.valueOf(1).equals(cfg.getDeleted())) {
            throw new BizException("数据源配置不存在，id=" + dto.getId());
        }
        cfg.setEnabled(dto.getEnabled());
        cfg.setMinMarketCapUsd(dto.getMinMarketCapUsd());
        cfg.setMaxMarketCapUsd(dto.getMaxMarketCapUsd());
        cfg.setMinLiquidityUsd(dto.getMinLiquidityUsd());
        cfg.setMinVolumeLiquidityRatio(dto.getMinVolumeLiquidityRatio());
        cfg.setMinPriceChange1hPct(dto.getMinPriceChange1hPct());
        cfg.setPageSize(dto.getPageSize());
        cfg.setScanLimit(dto.getScanLimit());
        configMapper.updateById(cfg);
        log.info("[SourceConfig] Updated config for sourceId={}, enabled={}", cfg.getSourceId(), cfg.getEnabled());
    }

    @Override
    public ChainSourceConfig getBySourceId(String sourceId) {
        return configMapper.selectOne(
                new LambdaQueryWrapper<ChainSourceConfig>()
                        .eq(ChainSourceConfig::getSourceId, sourceId)
                        .eq(ChainSourceConfig::getDeleted, 0));
    }

    // ─── 内部工具 ──────────────────────────────────────────

    private ChainSourceConfigVO toVO(ChainSourceConfig cfg) {
        return ChainSourceConfigVO.builder()
                .id(cfg.getId())
                .sourceId(cfg.getSourceId())
                .sourceName(cfg.getSourceName())
                .enabled(cfg.getEnabled())
                .minMarketCapUsd(cfg.getMinMarketCapUsd())
                .maxMarketCapUsd(cfg.getMaxMarketCapUsd())
                .minLiquidityUsd(cfg.getMinLiquidityUsd())
                .minVolumeLiquidityRatio(cfg.getMinVolumeLiquidityRatio())
                .minPriceChange1hPct(cfg.getMinPriceChange1hPct())
                .pageSize(cfg.getPageSize())
                .scanLimit(cfg.getScanLimit())
                .build();
    }
}

package com.vertex.service.chain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.vertex.model.entity.chain.ChainTokenMetrics;
import org.apache.ibatis.annotations.Mapper;

/**
 * 链上代币指标快照 Mapper
 */
@Mapper
public interface ChainTokenMetricsMapper extends BaseMapper<ChainTokenMetrics> {
}

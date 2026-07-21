package com.vertex.service.strategy.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.vertex.model.entity.ai.AiConfig;
import org.apache.ibatis.annotations.Mapper;

/**
 * AI 模块动态配置 Mapper（单行表，id=1）。
 */
@Mapper
public interface AiConfigMapper extends BaseMapper<AiConfig> {
}

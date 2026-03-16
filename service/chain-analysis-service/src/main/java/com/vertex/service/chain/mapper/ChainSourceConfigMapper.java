package com.vertex.service.chain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.vertex.model.entity.chain.ChainSourceConfig;
import org.apache.ibatis.annotations.Mapper;

/**
 * 链上数据源配置 Mapper
 */
@Mapper
public interface ChainSourceConfigMapper extends BaseMapper<ChainSourceConfig> {
}

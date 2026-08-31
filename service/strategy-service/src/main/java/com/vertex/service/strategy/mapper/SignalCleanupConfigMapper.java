package com.vertex.service.strategy.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.vertex.model.entity.strategy.SignalCleanupConfig;
import org.apache.ibatis.annotations.Mapper;

/**
 * 信号清理配置 Mapper（单行表，id=1）。
 */
@Mapper
public interface SignalCleanupConfigMapper extends BaseMapper<SignalCleanupConfig> {
}

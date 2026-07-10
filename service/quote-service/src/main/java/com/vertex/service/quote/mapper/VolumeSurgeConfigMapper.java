package com.vertex.service.quote.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.vertex.model.entity.quote.VolumeSurgeConfig;
import org.apache.ibatis.annotations.Mapper;

/**
 * 币安现货成交量暴增扫描器动态配置 Mapper。
 * 单行表（id=1），大部分场景直接 selectById(1L) / updateById 即可。
 */
@Mapper
public interface VolumeSurgeConfigMapper extends BaseMapper<VolumeSurgeConfig> {
}

package com.vertex.service.strategy.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.vertex.model.entity.strategy.Signal;
import org.apache.ibatis.annotations.Mapper;

/**
 * 信号 Mapper
 */
@Mapper
public interface SignalMapper extends BaseMapper<Signal> {
}

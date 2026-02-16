package com.vertex.service.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.vertex.model.entity.trading.Position;
import org.apache.ibatis.annotations.Mapper;

/**
 * 持仓 Mapper
 */
@Mapper
public interface PositionMapper extends BaseMapper<Position> {
}

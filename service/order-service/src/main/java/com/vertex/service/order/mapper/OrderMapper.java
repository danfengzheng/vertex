package com.vertex.service.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.vertex.model.entity.trading.Order;
import org.apache.ibatis.annotations.Mapper;

/**
 * 交易订单 Mapper
 */
@Mapper
public interface OrderMapper extends BaseMapper<Order> {
}

package com.vertex.service.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.vertex.model.entity.trading.ExchangeAccount;
import org.apache.ibatis.annotations.Mapper;

/**
 * 交易所账户 Mapper
 */
@Mapper
public interface ExchangeAccountMapper extends BaseMapper<ExchangeAccount> {
}

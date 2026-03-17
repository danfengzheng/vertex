package com.vertex.service.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.vertex.model.entity.strategy.Strategy;
import org.apache.ibatis.annotations.Mapper;

/**
 * 策略表只读引用 Mapper（order-service 专用）
 * <p>
 * order-service 不依赖 strategy-service 模块，
 * 通过本 Mapper 直接读取 stg_strategy 表获取策略名称，
 * 避免跨模块编译依赖。运行时与 StrategyMapper 共享同一数据库连接。
 */
@Mapper
public interface StrategyRefMapper extends BaseMapper<Strategy> {
}

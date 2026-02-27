package com.vertex.service.chain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.vertex.model.entity.chain.AlertRule;
import org.apache.ibatis.annotations.Mapper;

/**
 * 链上告警规则 Mapper
 */
@Mapper
public interface AlertRuleMapper extends BaseMapper<AlertRule> {
}

package com.vertex.service.chain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.vertex.model.entity.chain.ChainToken;
import org.apache.ibatis.annotations.Mapper;

/**
 * 链上新发现代币 Mapper
 */
@Mapper
public interface ChainTokenMapper extends BaseMapper<ChainToken> {
}

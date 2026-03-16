package com.vertex.api.chain;

import com.vertex.model.dto.chain.ChainSourceConfigUpdateDTO;
import com.vertex.model.entity.chain.ChainSourceConfig;
import com.vertex.model.vo.chain.ChainSourceConfigVO;

import java.util.List;

/**
 * 链上数据源配置服务接口
 */
public interface IChainSourceConfigService {

    /**
     * 查询所有数据源配置
     */
    List<ChainSourceConfigVO> listAll();

    /**
     * 更新数据源配置
     */
    void update(ChainSourceConfigUpdateDTO dto);

    /**
     * 按 sourceId 获取配置（供数据源内部调用）
     */
    ChainSourceConfig getBySourceId(String sourceId);
}

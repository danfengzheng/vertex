package com.vertex.api.chain;

import com.vertex.model.dto.chain.ChainTokenQueryDTO;
import com.vertex.model.vo.chain.ChainTokenDetailVO;
import com.vertex.model.vo.chain.ChainTokenVO;
import com.vertex.common.core.page.PageResult;

/**
 * 链上新币分析服务接口
 */
public interface IChainTokenService {

    /**
     * 分页查询新币列表
     */
    PageResult<ChainTokenVO> page(ChainTokenQueryDTO query);

    /**
     * 查询新币详情（含最新指标快照）
     */
    ChainTokenDetailVO getById(Long id);

    /**
     * 手动触发指定链的扫描
     *
     * @param chain 链标识：BNB / SOLANA
     */
    void triggerScan(String chain);
}

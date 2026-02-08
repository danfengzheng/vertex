package com.vertex.api.strategy;

import com.vertex.common.core.page.PageResult;
import com.vertex.model.dto.strategy.SignalQueryDTO;
import com.vertex.model.vo.strategy.SignalVO;

/**
 * 信号服务接口
 */
public interface ISignalService {

    PageResult<SignalVO> page(SignalQueryDTO query);

    SignalVO getById(Long id);

    void triggerAnalysis(Long strategyId);
}

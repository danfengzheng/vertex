package com.vertex.api.trading;

import com.vertex.common.core.page.PageResult;
import com.vertex.model.dto.trading.PositionQueryDTO;
import com.vertex.model.vo.trading.PositionVO;

/**
 * 持仓服务接口
 */
public interface IPositionService {

    PositionVO getById(Long id);

    PageResult<PositionVO> page(PositionQueryDTO query);

    void close(Long id);
}

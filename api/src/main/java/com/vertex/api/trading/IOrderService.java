package com.vertex.api.trading;

import com.vertex.common.core.page.PageResult;
import com.vertex.model.dto.trading.OrderCreateDTO;
import com.vertex.model.dto.trading.OrderQueryDTO;
import com.vertex.model.vo.trading.OrderVO;

/**
 * 交易订单服务接口
 */
public interface IOrderService {

    Long create(OrderCreateDTO dto);

    void cancel(Long id);

    void confirm(Long id);

    void reject(Long id);

    OrderVO getById(Long id);

    PageResult<OrderVO> page(OrderQueryDTO query);
}

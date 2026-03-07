package com.vertex.api.strategy;

import com.vertex.common.core.page.PageResult;
import com.vertex.model.dto.strategy.StrategyCreateDTO;
import com.vertex.model.dto.strategy.StrategyQueryDTO;
import com.vertex.model.dto.strategy.StrategyUpdateDTO;
import com.vertex.model.vo.strategy.StrategyVO;

/**
 * 策略服务接口
 */
public interface IStrategyService {

    Long create(StrategyCreateDTO dto);

    void update(StrategyUpdateDTO dto);

    void delete(Long id);

    StrategyVO getById(Long id);

    PageResult<StrategyVO> page(StrategyQueryDTO query);

    void enable(Long id);

    void disable(Long id);

    /**
     * 复制策略，生成同配置的新策略（默认禁用，名称加"(副本)"后缀）
     *
     * @param id 源策略 ID
     * @return 新策略 ID
     */
    Long copy(Long id);
}

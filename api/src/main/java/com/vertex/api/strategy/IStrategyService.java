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
     * 切换策略的自动交易状态（只改 autoTrade 字段，不动其他配置）。
     * <p>
     * 与 update() 不同，此方法只精确设置 autoTrade，保留其他所有配置不变；
     * 避免前端切换 autoTrade 时误清空其他"允许清空"字段。
     * </p>
     *
     * @param id 策略 ID
     * @param autoTrade 0 = 关闭自动交易；1 = 开启自动交易
     */
    void setAutoTrade(Long id, Integer autoTrade);

    /**
     * 复制策略，生成同配置的新策略（默认禁用，名称加"(副本)"后缀）
     *
     * @param id 源策略 ID
     * @return 新策略 ID
     */
    Long copy(Long id);

    /**
     * 为所有当前运行中（enabled=1）的策略自动追加背离出场指标配置。
     * <p>
     * 若某策略的 exitIndicatorConfigs 中已存在 DIVERGENCE 类型的指标，则跳过不重复添加。
     * </p>
     *
     * @return 实际更新的策略数量
     */
    int addDivergenceExitToRunningStrategies();
}

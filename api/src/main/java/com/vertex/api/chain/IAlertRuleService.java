package com.vertex.api.chain;

import com.vertex.model.dto.chain.AlertRuleCreateDTO;
import com.vertex.model.dto.chain.AlertRuleUpdateDTO;
import com.vertex.model.query.PageQuery;
import com.vertex.model.vo.chain.AlertRuleVO;
import com.vertex.common.core.page.PageResult;

/**
 * 链上告警规则服务接口
 */
public interface IAlertRuleService {

    /**
     * 创建告警规则
     *
     * @return 规则ID
     */
    Long create(AlertRuleCreateDTO dto);

    /**
     * 更新告警规则
     */
    void update(AlertRuleUpdateDTO dto);

    /**
     * 删除告警规则（逻辑删除）
     */
    void delete(Long id);

    /**
     * 查询告警规则详情
     */
    AlertRuleVO getById(Long id);

    /**
     * 分页查询告警规则
     */
    PageResult<AlertRuleVO> page(PageQuery query);

    /**
     * 启用告警规则
     */
    void enable(Long id);

    /**
     * 禁用告警规则
     */
    void disable(Long id);
}

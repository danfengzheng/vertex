package com.vertex.api.strategy;

import com.vertex.common.core.page.PageResult;
import com.vertex.model.dto.strategy.SignalCursorDTO;
import com.vertex.model.dto.strategy.SignalQueryDTO;
import com.vertex.model.vo.strategy.SignalCursorResult;
import com.vertex.model.vo.strategy.SignalVO;

/**
 * 信号服务接口
 */
public interface ISignalService {

    PageResult<SignalVO> page(SignalQueryDTO query);

    /**
     * 游标（cursor / keyset）分页 —— 高频信号表的推荐查询方式。
     * <p>
     * 深页恒定时间；无需 COUNT。首次请求 cursor 留空，后续请求带上上次返回的 nextCursor。
     * </p>
     */
    SignalCursorResult<SignalVO> pageByCursor(SignalCursorDTO query);

    SignalVO getById(Long id);

    void triggerAnalysis(Long strategyId);
}

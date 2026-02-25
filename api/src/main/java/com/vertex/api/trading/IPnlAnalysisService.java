package com.vertex.api.trading;

import com.vertex.model.dto.trading.PnlAnalysisQueryDTO;
import com.vertex.model.vo.trading.DailyPnlVO;
import com.vertex.model.vo.trading.PnlAnalysisVO;

import java.util.List;

/**
 * 已平仓盈亏分析服务接口
 */
public interface IPnlAnalysisService {

    /**
     * 统计已平仓盈亏，支持按交易模式、策略、交易对、账户、平仓时间筛选
     */
    PnlAnalysisVO getPnlAnalysis(PnlAnalysisQueryDTO query);

    /**
     * 按日汇总已平仓盈亏，返回每日盈亏 + 累计盈亏时序数据（用于走势图）
     */
    List<DailyPnlVO> getDailyPnl(PnlAnalysisQueryDTO query);
}

package com.vertex.service.order.controller;

import com.vertex.api.trading.IPnlAnalysisService;
import com.vertex.model.dto.trading.PnlAnalysisQueryDTO;
import com.vertex.model.vo.trading.DailyPnlVO;
import com.vertex.model.vo.trading.PnlAnalysisVO;
import com.vertex.web.response.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 已平仓盈亏分析
 */
@Tag(name = "盈亏分析")
@Validated
@RestController
@RequestMapping("/admin/trade/pnl-analysis")
@RequiredArgsConstructor
public class PnlAnalysisController {

    private final IPnlAnalysisService pnlAnalysisService;

    @Operation(summary = "已平仓盈亏分析")
    @GetMapping
    public Result<PnlAnalysisVO> getPnlAnalysis(@Validated PnlAnalysisQueryDTO query) {
        return Result.success(pnlAnalysisService.getPnlAnalysis(query));
    }

    @Operation(summary = "按日汇总已平仓盈亏（走势图数据）")
    @GetMapping("/daily")
    public Result<List<DailyPnlVO>> getDailyPnl(@Validated PnlAnalysisQueryDTO query) {
        return Result.success(pnlAnalysisService.getDailyPnl(query));
    }
}

package com.vertex.service.farb.backtest;

import com.vertex.common.core.annotation.RequiresPermission;
import com.vertex.service.farb.signal.FarbDataCollector;
import com.vertex.web.response.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 资金费套利 - 回测 REST 接口。
 */
@Slf4j
@Tag(name = "资金费套利 - 回测")
@RestController("farbBacktestController")
@RequestMapping("/admin/farb/backtest")
@RequiredArgsConstructor
public class BacktestController {

    private final BacktestEngine engine;
    private final FarbDataCollector dataCollector;

    @RequiresPermission("farb:strategy")
    @PostMapping("/run")
    @Operation(summary = "运行回测（基于 RocksDB 里累积的历史信号数据）")
    public Result<BacktestResult> run(@RequestBody BacktestConfig config) {
        if (config.getStartTimeMs() == null || config.getEndTimeMs() == null
                || config.getEndTimeMs() <= config.getStartTimeMs()) {
            return Result.fail(400, "invalid time range");
        }
        long startNs = System.nanoTime();
        BacktestResult result = engine.run(config, dataCollector.getUniverse());
        long tookMs = (System.nanoTime() - startNs) / 1_000_000L;
        log.info("[BacktestController] backtest done: tookMs={} pairs={} netPnl={} sharpe={}",
                tookMs, result.getTotalPairsClosed(), result.getTotalNetPnl(), result.getSharpe());
        return Result.success(result);
    }
}

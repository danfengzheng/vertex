package com.vertex.service.strategy.backtest;

import com.alibaba.fastjson2.JSON;
import com.vertex.common.core.GlobalError;
import com.vertex.common.core.exception.BizException;
import com.vertex.model.dto.strategy.BacktestConfigDTO;
import com.vertex.model.dto.strategy.StrategyIndicatorConfig;
import com.vertex.model.entity.quote.KLine;
import com.vertex.model.entity.strategy.Signal;
import com.vertex.model.entity.strategy.SignalType;
import com.vertex.model.entity.strategy.Strategy;
import com.vertex.model.vo.strategy.BacktestResultVO;
import com.vertex.model.vo.strategy.BacktestResultVO.EquityPoint;
import com.vertex.model.vo.strategy.BacktestResultVO.TradeRecord;
import com.vertex.service.quote.store.KLineStore;
import com.vertex.service.strategy.engine.SignalGenerator;
import com.vertex.service.strategy.indicator.IndicatorRegistry;
import com.vertex.service.strategy.indicator.TechnicalIndicator;
import com.vertex.service.strategy.mapper.StrategyMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * 策略回测服务
 * <p>
 * 基于历史K线数据，模拟策略交易，计算收益指标。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BacktestService {

    private final StrategyMapper strategyMapper;
    private final KLineStore klineStore;
    private final IndicatorRegistry indicatorRegistry;
    private final SignalGenerator signalGenerator;

    /**
     * 执行策略回测
     */
    public BacktestResultVO runBacktest(BacktestConfigDTO config) {
        // 1. 加载策略
        Strategy strategy = strategyMapper.selectById(config.getStrategyId());
        if (strategy == null) {
            throw new BizException(GlobalError.STRATEGY_NOT_FOUND);
        }

        List<StrategyIndicatorConfig> indicatorConfigs = JSON.parseArray(
                strategy.getIndicatorConfigs(), StrategyIndicatorConfig.class);
        if (indicatorConfigs == null || indicatorConfigs.isEmpty()) {
            throw new BizException(GlobalError.STRATEGY_CONFIG_ERROR);
        }

        // 2. 计算所需历史数据量
        int requiredDataPoints = indicatorConfigs.stream()
                .mapToInt(ic -> {
                    TechnicalIndicator ind = indicatorRegistry.get(ic.getIndicatorType());
                    return ind.requiredDataPoints(ic.getParams());
                })
                .max()
                .orElse(50);

        // 3. 前推获取足够的K线数据
        long intervalMillis = strategy.getInterval().getMillis();
        long prefetchStart = config.getStartTime() - intervalMillis * (requiredDataPoints + 10);

        List<KLine> allKlines = klineStore.query(
                strategy.getExchange(),
                strategy.getSymbol(),
                strategy.getInterval(),
                prefetchStart,
                config.getEndTime(),
                Integer.MAX_VALUE
        );

        if (allKlines.size() < requiredDataPoints + 1) {
            throw new BizException(GlobalError.BACKTEST_INSUFFICIENT_DATA);
        }

        // 4. 执行模拟交易
        return simulateTrades(strategy, indicatorConfigs, allKlines, config, requiredDataPoints);
    }

    private BacktestResultVO simulateTrades(
            Strategy strategy,
            List<StrategyIndicatorConfig> configs,
            List<KLine> klines,
            BacktestConfigDTO config,
            int requiredDataPoints) {

        BigDecimal capital = config.getInitialCapital();
        BigDecimal position = BigDecimal.ZERO;
        BigDecimal entryPrice = BigDecimal.ZERO;
        Long entryTime = null;
        boolean inPosition = false;

        List<TradeRecord> trades = new ArrayList<>();
        List<EquityPoint> equityCurve = new ArrayList<>();

        BigDecimal peakEquity = capital;
        BigDecimal maxDrawdown = BigDecimal.ZERO;
        int maxDrawdownDuration = 0;
        int currentDrawdownDuration = 0;

        // 滑动窗口模拟
        for (int i = requiredDataPoints; i < klines.size(); i++) {
            KLine currentKline = klines.get(i);

            // 仅处理回测时间范围内的K线
            if (currentKline.getOpenTime() < config.getStartTime()) {
                continue;
            }

            // 取窗口数据计算信号
            List<KLine> window = klines.subList(Math.max(0, i - requiredDataPoints - 5), i + 1);
            Signal signal = signalGenerator.evaluate(strategy, configs, window);

            // 当前权益
            BigDecimal currentPrice = currentKline.getClose();
            BigDecimal currentEquity = inPosition
                    ? capital.add(position.multiply(currentPrice))
                    : capital;

            // 每 10 根K线记录一个资金曲线点（防止数据过多）
            if (equityCurve.isEmpty() || (i % 10 == 0)) {
                equityCurve.add(EquityPoint.builder()
                        .time(currentKline.getOpenTime())
                        .equity(currentEquity.setScale(2, RoundingMode.HALF_UP))
                        .build());
            }

            // 跟踪回撤
            if (currentEquity.compareTo(peakEquity) > 0) {
                peakEquity = currentEquity;
                currentDrawdownDuration = 0;
            } else {
                currentDrawdownDuration++;
                if (peakEquity.compareTo(BigDecimal.ZERO) > 0) {
                    BigDecimal drawdown = peakEquity.subtract(currentEquity)
                            .divide(peakEquity, 6, RoundingMode.HALF_UP)
                            .multiply(new BigDecimal("100"));
                    if (drawdown.compareTo(maxDrawdown) > 0) {
                        maxDrawdown = drawdown;
                        maxDrawdownDuration = currentDrawdownDuration;
                    }
                }
            }

            // 根据信号执行交易
            if (!inPosition && signal.getSignalType() == SignalType.BUY) {
                // 开仓
                BigDecimal tradeAmount = capital.multiply(config.getPositionRatio());
                BigDecimal fee = tradeAmount.multiply(config.getFeeRate());
                BigDecimal netAmount = tradeAmount.subtract(fee);
                position = netAmount.divide(currentPrice, 8, RoundingMode.DOWN);
                capital = capital.subtract(tradeAmount);
                entryPrice = currentPrice;
                entryTime = currentKline.getOpenTime();
                inPosition = true;

            } else if (inPosition && signal.getSignalType() == SignalType.SELL) {
                // 平仓
                BigDecimal exitValue = position.multiply(currentPrice);
                BigDecimal fee = exitValue.multiply(config.getFeeRate());
                BigDecimal netValue = exitValue.subtract(fee);

                BigDecimal cost = position.multiply(entryPrice);
                BigDecimal profit = netValue.subtract(cost);
                BigDecimal profitPercent = cost.compareTo(BigDecimal.ZERO) > 0
                        ? profit.divide(cost, 6, RoundingMode.HALF_UP).multiply(new BigDecimal("100"))
                        : BigDecimal.ZERO;

                trades.add(TradeRecord.builder()
                        .entryTime(entryTime)
                        .exitTime(currentKline.getOpenTime())
                        .type("LONG")
                        .entryPrice(entryPrice.setScale(8, RoundingMode.HALF_UP))
                        .exitPrice(currentPrice.setScale(8, RoundingMode.HALF_UP))
                        .quantity(position.setScale(8, RoundingMode.HALF_UP))
                        .profit(profit.setScale(2, RoundingMode.HALF_UP))
                        .profitPercent(profitPercent.setScale(2, RoundingMode.HALF_UP))
                        .build());

                capital = capital.add(netValue);
                position = BigDecimal.ZERO;
                inPosition = false;
            }
        }

        // 如果回测结束时仍有持仓，按最后价格平仓
        if (inPosition && !klines.isEmpty()) {
            BigDecimal lastPrice = klines.get(klines.size() - 1).getClose();
            BigDecimal exitValue = position.multiply(lastPrice);
            BigDecimal fee = exitValue.multiply(config.getFeeRate());
            capital = capital.add(exitValue.subtract(fee));

            BigDecimal cost = position.multiply(entryPrice);
            BigDecimal profit = exitValue.subtract(fee).subtract(cost);
            BigDecimal profitPercent = cost.compareTo(BigDecimal.ZERO) > 0
                    ? profit.divide(cost, 6, RoundingMode.HALF_UP).multiply(new BigDecimal("100"))
                    : BigDecimal.ZERO;

            trades.add(TradeRecord.builder()
                    .entryTime(entryTime)
                    .exitTime(klines.get(klines.size() - 1).getOpenTime())
                    .type("LONG")
                    .entryPrice(entryPrice.setScale(8, RoundingMode.HALF_UP))
                    .exitPrice(lastPrice.setScale(8, RoundingMode.HALF_UP))
                    .quantity(position.setScale(8, RoundingMode.HALF_UP))
                    .profit(profit.setScale(2, RoundingMode.HALF_UP))
                    .profitPercent(profitPercent.setScale(2, RoundingMode.HALF_UP))
                    .build());

            position = BigDecimal.ZERO;
        }

        // 添加最后一个资金曲线点
        equityCurve.add(EquityPoint.builder()
                .time(config.getEndTime())
                .equity(capital.setScale(2, RoundingMode.HALF_UP))
                .build());

        return calculateMetrics(strategy, config, capital, trades, equityCurve, maxDrawdown, maxDrawdownDuration);
    }

    private BacktestResultVO calculateMetrics(
            Strategy strategy,
            BacktestConfigDTO config,
            BigDecimal finalCapital,
            List<TradeRecord> trades,
            List<EquityPoint> equityCurve,
            BigDecimal maxDrawdown,
            int maxDrawdownDuration) {

        BigDecimal totalProfit = finalCapital.subtract(config.getInitialCapital());
        BigDecimal returnRate = config.getInitialCapital().compareTo(BigDecimal.ZERO) > 0
                ? totalProfit.divide(config.getInitialCapital(), 6, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"))
                : BigDecimal.ZERO;

        int totalTrades = trades.size();
        int winningTrades = (int) trades.stream()
                .filter(t -> t.getProfit().compareTo(BigDecimal.ZERO) > 0)
                .count();
        int losingTrades = (int) trades.stream()
                .filter(t -> t.getProfit().compareTo(BigDecimal.ZERO) < 0)
                .count();

        BigDecimal winRate = totalTrades > 0
                ? new BigDecimal(winningTrades).divide(new BigDecimal(totalTrades), 4, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"))
                : BigDecimal.ZERO;

        // 盈亏比 = 平均盈利 / 平均亏损
        BigDecimal avgWin = trades.stream()
                .filter(t -> t.getProfit().compareTo(BigDecimal.ZERO) > 0)
                .map(TradeRecord::getProfit)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal avgLoss = trades.stream()
                .filter(t -> t.getProfit().compareTo(BigDecimal.ZERO) < 0)
                .map(t -> t.getProfit().abs())
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal profitLossRatio;
        if (winningTrades > 0 && losingTrades > 0) {
            BigDecimal avgWinVal = avgWin.divide(new BigDecimal(winningTrades), 6, RoundingMode.HALF_UP);
            BigDecimal avgLossVal = avgLoss.divide(new BigDecimal(losingTrades), 6, RoundingMode.HALF_UP);
            profitLossRatio = avgLossVal.compareTo(BigDecimal.ZERO) > 0
                    ? avgWinVal.divide(avgLossVal, 2, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;
        } else {
            profitLossRatio = BigDecimal.ZERO;
        }

        // 简化夏普比率计算（假设无风险利率为0）
        BigDecimal sharpeRatio = BigDecimal.ZERO;
        if (trades.size() >= 2) {
            List<Double> returns = trades.stream()
                    .map(t -> t.getProfitPercent().doubleValue())
                    .toList();
            double mean = returns.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            double variance = returns.stream()
                    .mapToDouble(r -> (r - mean) * (r - mean))
                    .average()
                    .orElse(0);
            double stdDev = Math.sqrt(variance);
            if (stdDev > 0) {
                sharpeRatio = BigDecimal.valueOf(mean / stdDev).setScale(2, RoundingMode.HALF_UP);
            }
        }

        return BacktestResultVO.builder()
                .strategyId(strategy.getId())
                .strategyName(strategy.getName())
                .startTime(config.getStartTime())
                .endTime(config.getEndTime())
                .initialCapital(config.getInitialCapital().setScale(2, RoundingMode.HALF_UP))
                .finalCapital(finalCapital.setScale(2, RoundingMode.HALF_UP))
                .totalProfit(totalProfit.setScale(2, RoundingMode.HALF_UP))
                .returnRate(returnRate.setScale(2, RoundingMode.HALF_UP))
                .totalTrades(totalTrades)
                .winningTrades(winningTrades)
                .losingTrades(losingTrades)
                .winRate(winRate.setScale(2, RoundingMode.HALF_UP))
                .profitLossRatio(profitLossRatio)
                .maxDrawdown(maxDrawdown.setScale(2, RoundingMode.HALF_UP))
                .maxDrawdownDuration(maxDrawdownDuration)
                .sharpeRatio(sharpeRatio)
                .trades(trades)
                .equityCurve(equityCurve)
                .build();
    }
}

package com.vertex.api.trading;

import com.vertex.model.entity.quote.KLine;
import com.vertex.model.entity.strategy.Signal;
import com.vertex.model.entity.strategy.SignalType;
import com.vertex.model.entity.strategy.Strategy;

import java.math.BigDecimal;
import java.util.List;

/**
 * 交易执行监听器（解耦接口）
 * <p>
 * 放在 api 模块中，strategy-service 通过此接口通知 order-service 执行交易。
 * 通过 Spring 自动装配实现模块解耦。
 * </p>
 */
public interface ITradeExecutionListener {

    /**
     * 信号触发交易执行
     *
     * @param strategy 策略
     * @param signal   生成的信号
     */
    void onSignal(Strategy strategy, Signal signal);

    /**
     * K线收盘后触发移动ATR止损更新。
     * <p>
     * 仅当策略配置了移动止损四参数（initialStopMultiplier 不为null）时调用，
     * order-service 接收后更新该策略的所有 OPEN 持仓止损阶段。
     * </p>
     *
     * @param strategy 策略（含移动止损四参数）
     * @param atrValue 当前K线周期计算出的最新ATR值
     * @param klines   主周期K线（升序），用于获取当前收盘价
     */
    default void onKLineClose(Strategy strategy, BigDecimal atrValue, List<KLine> klines) {
        // 默认空实现：未启用交易时无需处理
    }

    /**
     * K线收盘后 SuperTrend 动态止损价更新。
     * <p>
     * 仅当策略配置了 superTrendSlOffsetPct（&gt; 0）且指标结果中包含 SUPERTREND 数据时，
     * 由 StrategyEngineService 在每次 K 线收盘评估后调用。
     * 实现方需：① 查找该策略的 OPEN 持仓；② 校验趋势方向与持仓方向是否一致；
     * ③ 一致时计算并写入 position.superTrendStopLoss；④ 趋势反转时跳过（冻结当前值）。
     * </p>
     *
     * @param strategy        策略（含 superTrendSlOffsetPct 配置）
     * @param superTrendValue SuperTrend 当前值（上升趋势=lowerBand，下降趋势=upperBand）
     * @param trendUp         true=上升趋势，false=下降趋势
     */
    default void onSuperTrendStopUpdate(Strategy strategy, BigDecimal superTrendValue, boolean trendUp) {
        // 默认空实现：未启用交易时无需处理
    }

    /**
     * 每根K线收盘后检查出场条件（时间止损 + 指标出场）。
     * <p>
     * 仅当 autoTrade=1 时由 StrategyEngineService 调用，与 K 线收盘事件联动。
     * 实现方需：① 更新各持仓的 openBarCount；② 检查 maxHoldingBars 时间止损；
     * ③ 根据 exitSignalType 判断方向并调用平仓。
     * </p>
     *
     * @param strategy        策略配置（含 maxHoldingBars 与 minSignalStrength）
     * @param exitSignalType  出场指标评估结果（NEUTRAL=无出场信号；SELL=多头平仓；BUY=空头平仓）
     * @param signalStrength  出场信号强度（0-100）
     */
    default void onExitConditionCheck(Strategy strategy, SignalType exitSignalType, int signalStrength) {
        // 默认空实现：未启用交易时无需处理
    }

    /**
     * NEUTRAL 信号时的补充平仓判据：根据入场指标投票分布（纯计数、不算权重）
     * 判断反向占比是否触及阈值 {@code strategy.exitOnOppositeVoteRatio}。
     * <p>
     * 由 {@code StrategyEngineService} 在生成的入场 signal 为 NEUTRAL 且策略配置了阈值时调用；
     * 实现方负责查该策略的 OPEN 持仓，按 side 计算反向指标个数，达阈值即 executeClose。
     * </p>
     *
     * @param strategy    策略（含 exitOnOppositeVoteRatio 阈值）
     * @param buyCount    入场信号投票分布：BUY 指标个数
     * @param sellCount   入场信号投票分布：SELL 指标个数
     * @param neutralCount 入场信号投票分布：NEUTRAL 指标个数（含 no_data 时的降级为 NEUTRAL）
     */
    default void onNeutralVoteExitCheck(Strategy strategy,
                                        int buyCount, int sellCount, int neutralCount) {
        // 默认空实现：未启用交易时无需处理
    }
}

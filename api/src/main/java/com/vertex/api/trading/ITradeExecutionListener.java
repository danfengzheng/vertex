package com.vertex.api.trading;

import com.vertex.model.entity.quote.KLine;
import com.vertex.model.entity.strategy.Signal;
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
}

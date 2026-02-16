package com.vertex.api.trading;

import com.vertex.model.entity.strategy.Signal;
import com.vertex.model.entity.strategy.Strategy;

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
}

package com.vertex.service.strategy.engine;

import com.vertex.model.entity.quote.KLine;
import com.vertex.model.entity.quote.KLineInterval;

import java.util.List;

/**
 * Bar 数据提供者接口
 * <p>
 * 屏蔽"bar 从哪来"的细节，IndicatorCalculationEngine 通过此接口获取 K 线数据，
 * 实盘和回测注入不同实现，指标计算引擎无需关心数据来源。
 * </p>
 *
 * <ul>
 *   <li>{@link LiveBarProvider}   — 实盘实现，查询 RocksDB（无状态）</li>
 *   <li>{@link com.vertex.service.strategy.backtest.BacktestBarProvider}
 *       — 回测实现，预加载历史数据 + 单调指针（有状态，O(1) getBars）</li>
 * </ul>
 */
public interface BarProvider {

    /**
     * 获取指定周期最近 maxCount 根已收盘 K 线（时间升序）。
     *
     * @param interval 目标 K 线周期
     * @param maxCount 最多返回的根数（实际可能少于此值）
     * @return 升序 K 线列表，不足时返回全部可用数据
     */
    List<KLine> getBars(KLineInterval interval, int maxCount);

    /**
     * 本次触发的 K 线 openTime（毫秒）。
     * 用于 PartialBar 构建的时间锚点，以及防止 @Async 延迟导致窗口漂移。
     * 手动触发时可为 null，引擎自动取最新 bar 的 openTime。
     */
    Long getTriggerTime();

    /** 交易所标识 */
    String getExchange();

    /** 交易对 */
    String getSymbol();
}

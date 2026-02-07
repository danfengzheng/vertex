package com.vertex.service.quote.source;

import com.vertex.model.entity.quote.KLineInterval;

/**
 * 行情数据源接口
 * <p>
 * 统一管理各交易所的实时数据接入和订阅，包括 WebSocket 实时推送和 REST 历史补全。
 */
public interface QuoteDataSource {

    /**
     * 交易所标识
     */
    String exchangeCode();

    /**
     * 启动数据源（建立连接）
     */
    void start();

    /**
     * 停止数据源（关闭连接）
     */
    void stop();

    /**
     * 订阅 K线数据
     *
     * @param symbol   统一交易对格式，如 BTC-USDT
     * @param interval K线周期
     */
    void subscribe(String symbol, KLineInterval interval);

    /**
     * 取消订阅 K线数据
     *
     * @param symbol   统一交易对格式
     * @param interval K线周期
     */
    void unsubscribe(String symbol, KLineInterval interval);

    /**
     * 是否已连接
     */
    boolean isConnected();
}

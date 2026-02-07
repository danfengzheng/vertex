package com.vertex.service.quote.source.rest;

import com.vertex.model.entity.quote.KLine;
import com.vertex.model.entity.quote.KLineInterval;

import java.util.List;

/**
 * K线 REST 客户端接口
 * <p>
 * 用于从交易所 REST API 获取历史 K线数据，
 * 主要用于数据补全和初始化回填。
 */
public interface KLineRestClient {

    /**
     * 交易所标识
     */
    String exchangeCode();

    /**
     * 获取历史 K线数据
     *
     * @param symbol    统一交易对格式，如 BTC-USDT
     * @param interval  K线周期
     * @param startTime 起始时间（毫秒），可为 null
     * @param endTime   结束时间（毫秒），可为 null
     * @param limit     最大条数
     * @return K线列表，按时间升序
     */
    List<KLine> fetchKLines(String symbol, KLineInterval interval,
                            Long startTime, Long endTime, int limit);
}

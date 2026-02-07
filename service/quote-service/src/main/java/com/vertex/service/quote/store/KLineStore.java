package com.vertex.service.quote.store;

import com.vertex.model.entity.quote.KLine;
import com.vertex.model.entity.quote.KLineInterval;

import java.util.List;

/**
 * K线数据存储接口
 */
public interface KLineStore {

    /**
     * 保存单条K线
     */
    void save(KLine kline);

    /**
     * 批量保存K线
     */
    void saveBatch(List<KLine> klines);

    /**
     * 范围查询K线
     *
     * @param exchange  交易所
     * @param symbol    交易对
     * @param interval  K线周期
     * @param startTime 起始时间（毫秒），null 不限
     * @param endTime   结束时间（毫秒），null 不限
     * @param limit     最大条数
     * @return K线列表，按时间升序
     */
    List<KLine> query(String exchange, String symbol, KLineInterval interval,
                      Long startTime, Long endTime, int limit);

    /**
     * 获取最新一条K线
     */
    KLine getLatest(String exchange, String symbol, KLineInterval interval);
}

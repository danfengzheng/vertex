package com.vertex.service.mm.market;

/**
 * 公有成交流事件回调 —— PaperFillSimulator 实现此接口。
 */
public interface AggTradeListener {
    void onAggTrade(AggTrade trade);
}

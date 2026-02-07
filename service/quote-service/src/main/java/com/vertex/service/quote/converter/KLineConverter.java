package com.vertex.service.quote.converter;

import com.vertex.model.entity.quote.KLine;
import com.vertex.model.entity.quote.KLineInterval;

import java.util.List;

/**
 * K线数据转换器接口
 * <p>
 * 将交易所原始数据转换为统一的 KLine 模型
 */
public interface KLineConverter {

    /**
     * 返回交易所标识
     */
    String exchangeCode();

    /**
     * 转换单条K线
     *
     * @param symbol   统一交易对格式，如 BTC-USDT
     * @param interval K线周期
     * @param rawData  交易所原始 JSON 数据
     * @return 统一 KLine 模型
     */
    KLine convert(String symbol, KLineInterval interval, String rawData);

    /**
     * 批量转换K线（REST API 返回的数组）
     *
     * @param symbol   统一交易对格式
     * @param interval K线周期
     * @param rawData  交易所原始 JSON 数组数据
     * @return 统一 KLine 列表
     */
    List<KLine> convertBatch(String symbol, KLineInterval interval, String rawData);
}

package com.vertex.service.quote.converter;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.vertex.model.entity.quote.KLine;
import com.vertex.model.entity.quote.KLineInterval;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 币安 K线数据转换器
 * <p>
 * 币安 WebSocket KLine 格式：
 * {"e":"kline","s":"BTCUSDT","k":{"t":开盘时间,"T":收盘时间,
 *   "o":"开盘价","h":"最高价","l":"最低价","c":"收盘价",
 *   "v":"成交量","q":"成交额","n":成交笔数,"x":是否完结}}
 * <p>
 * 币安 REST KLine 格式（数组的数组）：
 * [[开盘时间, 开, 高, 低, 收, 成交量, 收盘时间, 成交额, 成交笔数, ...], ...]
 */
@Slf4j
@Component
public class BinanceKLineConverter implements KLineConverter {

    @Override
    public String exchangeCode() {
        return "binance";
    }

    @Override
    public KLine convert(String symbol, KLineInterval interval, String rawData) {
        JSONObject json = JSON.parseObject(rawData);

        // WebSocket 推送格式
        JSONObject k = json.getJSONObject("k");
        if (k != null) {
            return KLine.builder()
                    .symbol(symbol)
                    .exchange(exchangeCode())
                    .interval(interval)
                    .openTime(k.getLong("t"))
                    .closeTime(k.getLong("T"))
                    .open(k.getBigDecimal("o"))
                    .high(k.getBigDecimal("h"))
                    .low(k.getBigDecimal("l"))
                    .close(k.getBigDecimal("c"))
                    .volume(k.getBigDecimal("v"))
                    .quoteVolume(k.getBigDecimal("q"))
                    .trades(k.getInteger("n"))
                    .closed(k.getBoolean("x"))
                    .build();
        }

        // 其他格式尝试直接解析
        return KLine.builder()
                .symbol(symbol)
                .exchange(exchangeCode())
                .interval(interval)
                .openTime(json.getLong("openTime"))
                .closeTime(json.getLong("closeTime"))
                .open(json.getBigDecimal("open"))
                .high(json.getBigDecimal("high"))
                .low(json.getBigDecimal("low"))
                .close(json.getBigDecimal("close"))
                .volume(json.getBigDecimal("volume"))
                .quoteVolume(json.getBigDecimal("quoteVolume"))
                .trades(json.getInteger("trades"))
                .closed(json.getBoolean("closed"))
                .build();
    }

    @Override
    public List<KLine> convertBatch(String symbol, KLineInterval interval, String rawData) {
        JSONArray array = JSON.parseArray(rawData);
        List<KLine> result = new ArrayList<>(array.size());

        for (int i = 0; i < array.size(); i++) {
            JSONArray item = array.getJSONArray(i);
            if (item == null || item.size() < 9) {
                continue;
            }
            result.add(KLine.builder()
                    .symbol(symbol)
                    .exchange(exchangeCode())
                    .interval(interval)
                    .openTime(item.getLong(0))
                    .open(new BigDecimal(item.getString(1)))
                    .high(new BigDecimal(item.getString(2)))
                    .low(new BigDecimal(item.getString(3)))
                    .close(new BigDecimal(item.getString(4)))
                    .volume(new BigDecimal(item.getString(5)))
                    .closeTime(item.getLong(6))
                    .quoteVolume(new BigDecimal(item.getString(7)))
                    .trades(item.getInteger(8))
                    .closed(true)
                    .build());
        }
        return result;
    }
}

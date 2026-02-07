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
 * OKX K线数据转换器
 * <p>
 * OKX WebSocket KLine 推送格式：
 * {"arg":{"channel":"candle1m","instId":"BTC-USDT"},
 *  "data":[["时间戳","开","高","低","收","成交量(币)","成交量(张)","成交额","confirm"]]}
 * <p>
 * OKX REST KLine 格式（与 data 数组相同）：
 * {"data":[["ts","o","h","l","c","vol","volCcy","volCcyQuote","confirm"],...]}
 */
@Slf4j
@Component
public class OkxKLineConverter implements KLineConverter {

    @Override
    public String exchangeCode() {
        return "okx";
    }

    @Override
    public KLine convert(String symbol, KLineInterval interval, String rawData) {
        JSONObject json = JSON.parseObject(rawData);
        JSONArray data = json.getJSONArray("data");

        if (data != null && !data.isEmpty()) {
            JSONArray item = data.getJSONArray(0);
            return parseItem(symbol, interval, item);
        }

        return null;
    }

    @Override
    public List<KLine> convertBatch(String symbol, KLineInterval interval, String rawData) {
        JSONObject json = JSON.parseObject(rawData);
        JSONArray data = json.getJSONArray("data");

        if (data == null) {
            // 尝试直接解析为数组
            data = JSON.parseArray(rawData);
        }

        List<KLine> result = new ArrayList<>();
        if (data == null) {
            return result;
        }

        for (int i = 0; i < data.size(); i++) {
            JSONArray item = data.getJSONArray(i);
            if (item != null) {
                KLine kline = parseItem(symbol, interval, item);
                if (kline != null) {
                    result.add(kline);
                }
            }
        }
        return result;
    }

    private KLine parseItem(String symbol, KLineInterval interval, JSONArray item) {
        if (item == null || item.size() < 7) {
            return null;
        }

        long openTime = item.getLong(0);
        // OKX confirm 字段: "1" 表示已完结
        String confirm = item.size() > 8 ? item.getString(8) : "1";

        return KLine.builder()
                .symbol(symbol)
                .exchange(exchangeCode())
                .interval(interval)
                .openTime(openTime)
                .closeTime(openTime + interval.getMillis())
                .open(new BigDecimal(item.getString(1)))
                .high(new BigDecimal(item.getString(2)))
                .low(new BigDecimal(item.getString(3)))
                .close(new BigDecimal(item.getString(4)))
                .volume(new BigDecimal(item.getString(5)))
                .quoteVolume(item.size() > 7 ? new BigDecimal(item.getString(7)) : BigDecimal.ZERO)
                .trades(null)
                .closed("1".equals(confirm))
                .build();
    }
}

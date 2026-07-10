package com.vertex.service.quote.scanner;

import com.alibaba.fastjson2.JSONArray;
import com.vertex.model.entity.quote.KLine;
import com.vertex.model.entity.quote.KLineInterval;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 把 Binance {@code /api/v3/klines} 返回的 12 元数组转成 {@link KLine} 实体。
 * <p>
 * Scanner 直接把整批 K 线塞进 {@code KLineStore}，所以需要这一层轻量转换 —
 * 不复用 {@code BinanceKLineConverter}，因为它是 WebSocket 流的语义，
 * 而 REST 拉取的 K 线**默认都是已收盘**（当前小时的最后一根除外）。
 * </p>
 * <p>
 * 输入格式：
 * <pre>
 * [openTime, open, high, low, close, volume, closeTime,
 *  quoteVolume, tradeCount, takerBuyBase, takerBuyQuote, ignored]
 * </pre>
 * </p>
 */
final class BinanceKlineRowConverter {

    private BinanceKlineRowConverter() {}

    /**
     * 把一批 Binance REST klines 转成 KLine 列表。
     * @param exchange   写入 KLineStore 时用的 exchange 标识（本模块统一 "binance"）
     * @param symbol     币安 raw symbol，例如 "BTCUSDT"（不带 dash；scanner 单独 namespace）
     * @param interval   K 线周期
     * @param nowMs      当前时刻，用于判定最后一根是否已收盘
     * @param rows       Binance REST 原始返回
     */
    static List<KLine> convert(String exchange, String symbol, KLineInterval interval,
                               long nowMs, JSONArray rows) {
        if (rows == null || rows.isEmpty()) return new ArrayList<>();
        List<KLine> out = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            JSONArray row = rows.getJSONArray(i);
            if (row == null || row.size() < 8) continue;
            try {
                long openTime = row.getLong(0);
                long closeTime = row.getLong(6);
                out.add(KLine.builder()
                        .exchange(exchange)
                        .symbol(symbol)
                        .interval(interval)
                        .openTime(openTime)
                        .closeTime(closeTime)
                        .open(row.getBigDecimal(1))
                        .high(row.getBigDecimal(2))
                        .low(row.getBigDecimal(3))
                        .close(row.getBigDecimal(4))
                        .volume(row.getBigDecimal(5))
                        .quoteVolume(row.getBigDecimal(7))
                        .trades(row.getIntValue(8))
                        .closed(closeTime < nowMs)
                        .build());
            } catch (Exception ignored) {
                // 单条格式异常直接跳过
            }
        }
        return out;
    }
}

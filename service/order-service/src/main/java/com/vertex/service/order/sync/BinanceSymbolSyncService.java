package com.vertex.service.order.sync;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.vertex.model.entity.trading.TrdExchangeSymbol;
import com.vertex.model.entity.trading.TrdSymbol;
import com.vertex.service.order.mapper.TrdExchangeSymbolMapper;
import com.vertex.service.order.mapper.TrdSymbolMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Binance 币对同步服务
 * <p>
 * 调用 Binance 公开 exchangeInfo 接口（无需鉴权）获取全量交易对，
 * 写入 trd_symbol（通用标识）和 trd_exchange_symbol（交易所映射）。
 * <p>
 * 覆盖范围：
 * <ul>
 *   <li>SPOT：/api/v3/exchangeInfo — 仅保留 quoteAsset=USDT 的 TRADING 对</li>
 *   <li>USDM：/fapi/v1/exchangeInfo — 所有 TRADING 永续合约</li>
 *   <li>COINM：/dapi/v1/exchangeInfo — 所有 TRADING 币本位合约</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BinanceSymbolSyncService {

    private static final String EXCHANGE = "binance";

    private static final String SPOT_URL  = "https://api.binance.com/api/v3/exchangeInfo";
    private static final String USDM_URL  = "https://fapi.binance.com/fapi/v1/exchangeInfo";
    private static final String COINM_URL = "https://dapi.binance.com/dapi/v1/exchangeInfo";

    private final OkHttpClient httpClient;
    private final TrdSymbolMapper symbolMapper;
    private final TrdExchangeSymbolMapper exchangeSymbolMapper;

    /**
     * 执行全量同步，返回本次写入/更新的记录数。
     */
    public int syncAll() {
        log.info("[BinanceSync] Starting full symbol sync");

        // 一次性加载 trd_symbol 已有记录，三个市场共用，避免重复查表
        Set<String> existingSymbols = symbolMapper.selectList(
                        new LambdaQueryWrapper<TrdSymbol>().eq(TrdSymbol::getDeleted, 0))
                .stream().map(TrdSymbol::getSymbol).collect(Collectors.toSet());

        int total = 0;
        total += syncMarket("SPOT",  SPOT_URL,  true,  existingSymbols);
        total += syncMarket("USDM",  USDM_URL,  false, existingSymbols);
        total += syncMarket("COINM", COINM_URL, false, existingSymbols);
        log.info("[BinanceSync] Sync complete, total upserted: {}", total);
        return total;
    }

    // ─── 私有方法 ─────────────────────────────────────────────

    private int syncMarket(String marketType, String url, boolean usdtOnly, Set<String> existingSymbols) {
        JSONArray symbols = fetchSymbols(url);
        if (symbols == null || symbols.isEmpty()) {
            log.warn("[BinanceSync] No symbols returned for {}", marketType);
            return 0;
        }

        // 解析所有 TRADING 的交易对
        List<RawSymbol> rawList = new ArrayList<>();
        for (int i = 0; i < symbols.size(); i++) {
            JSONObject s = symbols.getJSONObject(i);
            if (!"TRADING".equals(s.getString("status"))) continue;
            String sym        = s.getString("symbol");
            String baseAsset  = s.getString("baseAsset");
            String quoteAsset = s.getString("quoteAsset");
            if (sym == null || baseAsset == null || quoteAsset == null) continue;
            if (usdtOnly && !"USDT".equals(quoteAsset)) continue;

            RawSymbol raw = new RawSymbol();
            raw.symbol      = sym;
            raw.baseAsset   = baseAsset;
            raw.quoteAsset  = quoteAsset;
            raw.status      = "TRADING";
            parseFilters(s.getJSONArray("filters"), raw);
            rawList.add(raw);
        }

        if (rawList.isEmpty()) return 0;

        // 1. 更新 trd_symbol（通用标识表）— 复用传入的 existingSymbols，三轮共享，避免重复查表
        upsertSymbols(rawList, existingSymbols);

        // 2. 更新 trd_exchange_symbol（映射表）
        int count = upsertExchangeSymbols(marketType, rawList);
        log.info("[BinanceSync] {} {} symbols synced", count, marketType);
        return count;
    }

    private void upsertSymbols(List<RawSymbol> rawList, Set<String> existingSymbols) {
        for (RawSymbol raw : rawList) {
            if (!existingSymbols.contains(raw.symbol)) {
                // 优先检查是否存在软删除记录：若有则恢复，避免 uk_symbol 唯一键冲突
                TrdSymbol deletedRecord = symbolMapper.selectDeletedBySymbol(raw.symbol);
                if (deletedRecord != null) {
                    symbolMapper.restoreById(deletedRecord.getId());
                    log.debug("[BinanceSync] Restored deleted trd_symbol: {}", raw.symbol);
                } else {
                    TrdSymbol ts = new TrdSymbol();
                    ts.setSymbol(raw.symbol);
                    ts.setBaseAsset(raw.baseAsset);
                    ts.setQuoteAsset(raw.quoteAsset);
                    symbolMapper.insert(ts);
                }
                existingSymbols.add(raw.symbol); // 更新本地 set，防止 USDM/COINM 重复处理同一 symbol
            }
        }
    }

    private int upsertExchangeSymbols(String marketType, List<RawSymbol> rawList) {
        // 加载当前 exchange + marketType 的全量数据（含软删除记录），构建查找 Map
        // 使用自定义查询绕过 @TableLogic，避免软删除记录重新 INSERT 时命中 uk_exchange_symbol_type 唯一键冲突
        List<TrdExchangeSymbol> existingList = exchangeSymbolMapper.selectAllByExchangeAndType(EXCHANGE, marketType);
        Map<String, TrdExchangeSymbol> existingMap = existingList.stream()
                .collect(Collectors.toMap(TrdExchangeSymbol::getSymbol, e -> e, (a, b) -> a));

        LocalDateTime now = LocalDateTime.now();
        List<TrdExchangeSymbol> toInsert = new ArrayList<>();
        List<TrdExchangeSymbol> toUpdate = new ArrayList<>();

        for (RawSymbol raw : rawList) {
            TrdExchangeSymbol existing = existingMap.get(raw.symbol);
            if (existing != null) {
                // 更新可变字段
                existing.setExchangeSymbol(raw.symbol); // Binance canonical == exchangeSymbol
                existing.setStatus(raw.status);
                existing.setLotSize(raw.lotSize);
                existing.setTickSize(raw.tickSize);
                existing.setMinNotional(raw.minNotional);
                existing.setSyncTime(now);
                toUpdate.add(existing);
            } else {
                TrdExchangeSymbol es = new TrdExchangeSymbol();
                es.setSymbol(raw.symbol);
                es.setExchange(EXCHANGE);
                es.setMarketType(marketType);
                es.setExchangeSymbol(raw.symbol); // Binance: canonical == exchangeSymbol
                es.setStatus(raw.status);
                es.setLotSize(raw.lotSize);
                es.setTickSize(raw.tickSize);
                es.setMinNotional(raw.minNotional);
                es.setSyncTime(now);
                toInsert.add(es);
            }
        }

        toInsert.forEach(exchangeSymbolMapper::insert);
        // 使用自定义 updateAndRestoreById：一次操作同时更新字段并将 deleted 置回 0，
        // 确保软删除记录能被正确恢复，同时绕过 @TableLogic 为 updateById 追加的 WHERE deleted=0 限制
        toUpdate.forEach(exchangeSymbolMapper::updateAndRestoreById);

        return toInsert.size() + toUpdate.size();
    }

    private JSONArray fetchSymbols(String url) {
        try {
            Request request = new Request.Builder().url(url).get().build();
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    log.error("[BinanceSync] exchangeInfo failed: {} code={}", url, response.code());
                    return null;
                }
                ResponseBody body = response.body();
                if (body == null) return null;
                JSONObject json = JSON.parseObject(body.string());
                return json.getJSONArray("symbols");
            }
        } catch (Exception e) {
            log.error("[BinanceSync] Failed to fetch {}: {}", url, e.getMessage(), e);
            return null;
        }
    }

    private void parseFilters(JSONArray filters, RawSymbol raw) {
        if (filters == null) return;
        for (int i = 0; i < filters.size(); i++) {
            JSONObject f = filters.getJSONObject(i);
            String type = f.getString("filterType");
            if ("LOT_SIZE".equals(type)) {
                String step = f.getString("stepSize");
                if (step != null) raw.lotSize = new BigDecimal(step);
            } else if ("PRICE_FILTER".equals(type)) {
                String tick = f.getString("tickSize");
                if (tick != null) raw.tickSize = new BigDecimal(tick);
            } else if ("MIN_NOTIONAL".equals(type) || "NOTIONAL".equals(type)) {
                String notional = f.getString("minNotional");
                if (notional == null) notional = f.getString("notional");
                if (notional != null) raw.minNotional = new BigDecimal(notional);
            }
        }
    }

    /** 内部解析结构，不对外暴露 */
    private static class RawSymbol {
        String symbol;
        String baseAsset;
        String quoteAsset;
        String status;
        BigDecimal lotSize;
        BigDecimal tickSize;
        BigDecimal minNotional;
    }
}

package com.vertex.service.strategy.engine;

import com.vertex.model.entity.quote.KLine;
import com.vertex.model.entity.quote.KLineInterval;
import com.vertex.service.quote.store.KLineStore;
import com.vertex.service.strategy.config.StrategyProperties;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 实盘 BarProvider 实现（无状态）
 * <p>
 * 每次调用直接查询 RocksDB，closed-only 过滤和升序排列均在此完成，
 * 上层（IndicatorCalculationEngine）无需关心存储细节。
 * </p>
 * <p>
 * 未来若行情模块增加内存 bar 缓存，只需修改此类的 getBars() 实现，
 * 所有指标计算自动受益，无需改动指标代码。
 * </p>
 */
public class LiveBarProvider implements BarProvider {

    private final KLineStore klineStore;
    private final StrategyProperties properties;
    private final String exchange;
    private final String symbol;
    private final Long triggerTime;

    public LiveBarProvider(KLineStore klineStore,
                           StrategyProperties properties,
                           String exchange,
                           String symbol,
                           Long triggerTime) {
        this.klineStore = klineStore;
        this.properties = properties;
        this.exchange = exchange;
        this.symbol = symbol;
        this.triggerTime = triggerTime;
    }

    /**
     * 获取指定周期的 K 线（已收盘，升序）。
     * <p>
     * 对高于主周期的二级周期，调用方（IndicatorCalculationEngine）在传入 endTime 前
     * 已将其前移一个周期（避免 Lookahead Bias），此处直接使用 triggerTime 作为上界即可。
     * </p>
     */
    @Override
    public List<KLine> getBars(KLineInterval interval, int maxCount) {
        // 降序查询（最新 → 最旧），closed-only 时多取 1 根以确保过滤后数量足够
        int querySize = properties.getEngine().isOnlyClosedKlines() ? maxCount + 1 : maxCount;
        List<KLine> klines = klineStore.query(
                exchange, symbol, interval, null, triggerTime, querySize, false);

        // 过滤未收盘 bar
        // 使用 !Boolean.FALSE.equals() 而非 Boolean.TRUE.equals()：
        // 历史 K 线（RocksDB 旧数据）closed 字段可能为 null（早期序列化未写入该字段），
        // null 应视为已收盘（历史数据必然已收盘），仅显式 closed=false 的实时推送 bar 才排除。
        if (properties.getEngine().isOnlyClosedKlines()) {
            klines = klines.stream()
                    .filter(k -> !Boolean.FALSE.equals(k.getClosed()))
                    .toList();
        }

        // 截断到 maxCount（降序头部 = 最新 maxCount 根）
        if (klines.size() > maxCount) {
            klines = klines.subList(0, maxCount);
        }

        // 翻转为升序（时间从早到晚），与指标计算约定一致
        List<KLine> ascending = new ArrayList<>(klines);
        Collections.reverse(ascending);
        return ascending;
    }

    @Override
    public Long getTriggerTime() {
        return triggerTime;
    }

    @Override
    public String getExchange() {
        return exchange;
    }

    @Override
    public String getSymbol() {
        return symbol;
    }
}

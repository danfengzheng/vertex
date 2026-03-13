package com.vertex.service.strategy.backtest;

import com.vertex.model.entity.quote.KLine;
import com.vertex.model.entity.quote.KLineInterval;
import com.vertex.service.strategy.engine.BarProvider;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 回测专用 BarProvider（有状态）
 * <p>
 * 预加载全量历史 K 线，通过单调指针 {@link #advance} 推进当前窗口，
 * {@link #getBars} 返回 O(1) subList 视图，总时间复杂度保持 O(n+m)
 * （n = 主周期 bar 数量，m = 各二级周期 bar 数量之和）。
 * </p>
 *
 * <h3>使用方式</h3>
 * <pre>
 * BacktestBarProvider barProvider = new BacktestBarProvider(allKlines, exchange, symbol);
 * for (int i = startIdx; i < mainKlines.size(); i++) {
 *     KLine current = mainKlines.get(i);
 *     barProvider.advance(current.getOpenTime(), strategy.getInterval());
 *     List&lt;IndicatorResult&gt; results = indicatorEngine.computeAll(strategy, configs, barProvider);
 *     Signal signal = signalGenerator.evaluate(strategy, configs, results);
 *     // 执行交易模拟...
 * }
 * </pre>
 *
 * <h3>指针推进规则</h3>
 * <ul>
 *   <li>主周期 / 小周期：推进到 openTime &le; triggerTime 的 bar</li>
 *   <li>大周期（二级）：推进到 closeTime &lt; triggerTime 的 bar（消除 Lookahead Bias）</li>
 * </ul>
 */
public class BacktestBarProvider implements BarProvider {

    /** 预加载的全量历史数据（按周期，升序） */
    private final Map<KLineInterval, List<KLine>> allKlines;

    /**
     * 单调推进指针：ptr = 已纳入窗口的 bar 数量（即下一个待处理 bar 的下标）。
     * 指针只增不减，确保 O(n+m) 总复杂度。
     */
    private final Map<KLineInterval, Integer> pointers = new HashMap<>();

    private final String exchange;
    private final String symbol;
    private long currentTriggerTime;

    public BacktestBarProvider(Map<KLineInterval, List<KLine>> allKlines,
                               String exchange,
                               String symbol) {
        this.allKlines = allKlines;
        this.exchange = exchange;
        this.symbol = symbol;
        allKlines.keySet().forEach(iv -> pointers.put(iv, 0));
    }

    /**
     * 推进各周期指针到当前触发时间。
     * 由 BacktestService 在每根主周期 K 线迭代开始时调用。
     *
     * @param triggerTime     当前主周期 bar 的 openTime
     * @param primaryInterval 策略主周期
     */
    public void advance(long triggerTime, KLineInterval primaryInterval) {
        this.currentTriggerTime = triggerTime;
        for (KLineInterval iv : allKlines.keySet()) {
            List<KLine> bars = allKlines.get(iv);
            int ptr = pointers.getOrDefault(iv, 0);

            if (iv.getMillis() > primaryInterval.getMillis()) {
                // 大周期：只推进已完整收盘的 bar（closeTime < triggerTime）
                while (ptr < bars.size()) {
                    KLine k = bars.get(ptr);
                    if (k.getCloseTime() != null && k.getCloseTime() < triggerTime) {
                        ptr++;
                    } else {
                        break;
                    }
                }
            } else {
                // 主周期 / 小周期：推进到 openTime <= triggerTime
                while (ptr < bars.size() && bars.get(ptr).getOpenTime() <= triggerTime) {
                    ptr++;
                }
            }
            pointers.put(iv, ptr);
        }
    }

    /**
     * O(1) 返回当前窗口最近 maxCount 根 bar 的 subList 视图（升序，不拷贝）。
     */
    @Override
    public List<KLine> getBars(KLineInterval interval, int maxCount) {
        List<KLine> bars = allKlines.getOrDefault(interval, Collections.emptyList());
        int ptr = pointers.getOrDefault(interval, 0);
        int from = Math.max(0, ptr - maxCount);
        return bars.subList(from, ptr);
    }

    @Override
    public Long getTriggerTime() {
        return currentTriggerTime;
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

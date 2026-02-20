package com.vertex.service.quote.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.vertex.api.quote.IKLineService;
import com.vertex.common.core.GlobalError;
import com.vertex.common.core.exception.BizException;
import com.vertex.model.dto.quote.KLineQueryDTO;
import com.vertex.model.entity.quote.KLine;
import com.vertex.model.entity.quote.KLineInterval;
import com.vertex.model.vo.quote.KLineVO;
import com.vertex.service.quote.aggregator.InMemoryKLineAggregator;
import com.vertex.service.quote.notify.CompositeNotifier;
import com.vertex.service.quote.store.KLineStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * K线数据服务实现
 * <p>
 * 读路径：内存（日 K 以下聚合器）→ RocksDB → 缺数仍由现有 backfill/StrategyDataWarmup 补。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KLineServiceImpl implements IKLineService {

    private final KLineStore klineStore;
    private final CompositeNotifier notifier;
    private final InMemoryKLineAggregator aggregator;

    @Override
    public List<KLineVO> query(KLineQueryDTO query) {
        int limit = query.getLimit() != null ? query.getLimit() : 500;
        String exchange = query.getExchange();
        String symbol = query.getSymbol();
        KLineInterval interval = query.getInterval();

        List<KLine> klines = klineStore.query(
                exchange, symbol, interval,
                query.getStartTime(), query.getEndTime(),
                limit, false);

        if (aggregator != null && InMemoryKLineAggregator.isIntraday(interval)) {
            KLine memLatest = aggregator.getLatest(exchange, symbol, interval);
            if (memLatest != null && (klines.isEmpty() || klines.get(0).getOpenTime() < memLatest.getOpenTime())) {
                List<KLine> merged = new ArrayList<>(limit);
                merged.add(memLatest);
                for (KLine k : klines) {
                    if (merged.size() >= limit) break;
                    if (!k.getOpenTime().equals(memLatest.getOpenTime())) {
                        merged.add(k);
                    }
                }
                klines = merged;
            }
        }

        return klines.stream().map(this::toVO).collect(Collectors.toList());
    }

    @Override
    public KLineVO getLatest(String symbol, String exchange, KLineInterval interval) {
        if (aggregator != null && InMemoryKLineAggregator.isIntraday(interval)) {
            KLine mem = aggregator.getLatest(exchange, symbol, interval);
            if (mem != null) {
                return toVO(mem);
            }
        }
        KLine kline = klineStore.getLatest(exchange, symbol, interval);
        if (kline == null) {
            throw new BizException(GlobalError.KLINE_NOT_FOUND);
        }
        return toVO(kline);
    }

    @Override
    public void save(KLine kline) {
        klineStore.save(kline);
        notifier.notifyKLine(kline);
        log.debug("Saved and notified KLine: {}:{}:{}", kline.getExchange(),
                kline.getSymbol(), kline.getInterval().getCode());
    }

    @Override
    public void saveBatch(List<KLine> klines) {
        if (klines == null || klines.isEmpty()) {
            return;
        }
        klineStore.saveBatch(klines);
        notifier.notifyKLineBatch(klines);
        log.debug("Saved and notified batch KLine, size: {}", klines.size());
    }

    @Override
    public int saveBatchFillingGapsOnly(List<KLine> klines) {
        if (klines == null || klines.isEmpty()) {
            return 0;
        }
        List<KLine> saved = klineStore.saveBatchFillingGapsOnly(klines);
        if (!saved.isEmpty()) {
            notifier.notifyKLineBatch(saved);
            log.debug("Saved gaps only, inserted: {}, skipped: {}", saved.size(), klines.size() - saved.size());
        }
        return saved.size();
    }

    /**
     * KLine → KLineVO 转换
     */
    private KLineVO toVO(KLine kline) {
        return BeanUtil.copyProperties(kline, KLineVO.class);
    }
}

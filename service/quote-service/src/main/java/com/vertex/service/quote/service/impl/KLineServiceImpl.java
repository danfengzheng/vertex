package com.vertex.service.quote.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.vertex.api.quote.IKLineService;
import com.vertex.common.core.GlobalError;
import com.vertex.common.core.exception.BizException;
import com.vertex.model.dto.quote.KLineQueryDTO;
import com.vertex.model.entity.quote.KLine;
import com.vertex.model.entity.quote.KLineInterval;
import com.vertex.model.vo.quote.KLineVO;
import com.vertex.service.quote.notify.CompositeNotifier;
import com.vertex.service.quote.store.KLineStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * K线数据服务实现
 * <p>
 * 串联 存储层 和 通知层，提供统一的 K线数据 CRUD 和通知分发。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KLineServiceImpl implements IKLineService {

    private final KLineStore klineStore;
    private final CompositeNotifier notifier;

    @Override
    public List<KLineVO> query(KLineQueryDTO query) {
        int limit = query.getLimit() != null ? query.getLimit() : 500;

        List<KLine> klines = klineStore.query(
                query.getExchange(),
                query.getSymbol(),
                query.getInterval(),
                query.getStartTime(),
                query.getEndTime(),
                limit
        );

        return klines.stream()
                .map(this::toVO)
                .collect(Collectors.toList());
    }

    @Override
    public KLineVO getLatest(String symbol, String exchange, KLineInterval interval) {
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

    /**
     * KLine → KLineVO 转换
     */
    private KLineVO toVO(KLine kline) {
        return BeanUtil.copyProperties(kline, KLineVO.class);
    }
}

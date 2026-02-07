package com.vertex.api.quote;

import com.vertex.model.dto.quote.KLineQueryDTO;
import com.vertex.model.entity.quote.KLine;
import com.vertex.model.entity.quote.KLineInterval;
import com.vertex.model.vo.quote.KLineVO;

import java.util.List;

/**
 * K线数据服务接口
 */
public interface IKLineService {

    /**
     * 查询K线数据
     */
    List<KLineVO> query(KLineQueryDTO query);

    /**
     * 获取最新一条K线
     */
    KLineVO getLatest(String symbol, String exchange, KLineInterval interval);

    /**
     * 保存单条K线（存储 + 通知）
     */
    void save(KLine kline);

    /**
     * 批量保存K线（存储 + 通知）
     */
    void saveBatch(List<KLine> klines);
}

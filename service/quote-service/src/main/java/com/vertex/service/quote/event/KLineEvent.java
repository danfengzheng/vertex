package com.vertex.service.quote.event;

import com.vertex.model.entity.quote.KLine;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.util.Collections;
import java.util.List;

/**
 * K线更新事件
 * <p>
 * 用于 Spring Event 机制在模块内或跨模块传递 KLine 数据更新通知。
 * 支持单条和批量两种模式。
 */
@Getter
public class KLineEvent extends ApplicationEvent {

    /** 本次更新的 KLine 数据列表 */
    private final List<KLine> klines;

    /**
     * 单条 KLine 更新事件
     */
    public KLineEvent(Object source, KLine kline) {
        super(source);
        this.klines = Collections.singletonList(kline);
    }

    /**
     * 批量 KLine 更新事件
     */
    public KLineEvent(Object source, List<KLine> klines) {
        super(source);
        this.klines = klines;
    }

    /**
     * 是否为批量事件
     */
    public boolean isBatch() {
        return klines != null && klines.size() > 1;
    }

    /**
     * 获取第一条 KLine（便捷方法）
     */
    public KLine getFirst() {
        return (klines != null && !klines.isEmpty()) ? klines.get(0) : null;
    }
}

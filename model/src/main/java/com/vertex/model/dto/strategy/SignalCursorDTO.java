package com.vertex.model.dto.strategy;

import com.vertex.model.entity.quote.KLineInterval;
import com.vertex.model.entity.strategy.SignalType;
import lombok.Data;

/**
 * 信号游标（cursor / keyset）分页查询参数。
 * <p>
 * 与 {@link SignalQueryDTO}（offset 分页）分工：cursor 分页无需 COUNT，深页恒定时间，
 * 适合"信号监控-加载更多"这种时序数据流的 UX。cursor 结构：
 * <ul>
 *   <li>{@code cursorTime}：上一页最后一条 signalTime；null = 从最新开始</li>
 *   <li>{@code cursorId}：上一页最后一条 id；null = 从最新开始。跟 cursorTime 一起用于
 *       组合排序键 (signal_time DESC, id DESC) 的严格下界。</li>
 * </ul>
 * 首次请求两个 cursor 都传 null；后端返回 {@code nextCursor}，前端下次带上即可。
 * </p>
 */
@Data
public class SignalCursorDTO {

    /** 每页大小，默认 20，最大 200 */
    private Integer pageSize = 20;

    /** 游标：上一页最后一条的 signalTime（null = 首次请求） */
    private Long cursorTime;

    /** 游标：上一页最后一条的 id（null = 首次请求） */
    private Long cursorId;

    // ── 过滤器（与 SignalQueryDTO 保持字段一致，方便前端复用）─────
    private Long strategyId;
    private String exchange;
    private String symbol;
    private KLineInterval interval;
    private SignalType signalType;
    private Long startTime;
    private Long endTime;
}

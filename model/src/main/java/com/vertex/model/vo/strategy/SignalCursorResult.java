package com.vertex.model.vo.strategy;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;

/**
 * 信号游标分页返回。
 * <p>
 * 与 {@link com.vertex.common.core.page.PageResult} 分工：本 VO 专用于游标分页，
 * 不再有 total / hasNext 概念，而是暴露 nextCursor —— 客户端下次带上即可拿下一页。
 * </p>
 * <p>
 * nextCursor 编码：{@code "{signalTime}_{id}"}；records 为空或 nextCursor 为 null 表示到底。
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SignalCursorResult<T> implements Serializable {

    private List<T> records;

    /** 下一页游标；null 表示已到最后 */
    private String nextCursor;

    /** 便利字段（前端 hasMoreButton 判定），等价于 nextCursor != null */
    private Boolean hasNext;

    public static <T> SignalCursorResult<T> empty() {
        return SignalCursorResult.<T>builder()
                .records(Collections.emptyList())
                .nextCursor(null)
                .hasNext(false)
                .build();
    }
}

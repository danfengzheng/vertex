package com.vertex.common.core.page;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Collections;
import java.util.List;

/**
 * PageResult
 *
 * @author eth
 * @version 1.0
 * @description
 * @date 2026/1/13 23:36
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PageResult<T> {
    /**
     * 总条数。
     * <ul>
     *   <li>传统 offset 分页：COUNT(*) 结果</li>
     *   <li>跳过 COUNT 的分页（高频表如 signal / order）：可能为 null，前端用 {@link #hasNext} 判定翻页</li>
     * </ul>
     */
    private Long total;

    /**
     * 是否还有下一页。仅在跳过 COUNT 的分页里由后端计算返回（fetch pageSize+1 检测）；
     * 传统 offset 分页默认为 null，前端可直接靠 total 判定。
     */
    private Boolean hasNext;

    private List<T> records;

    /** 传统构造：total 已知，hasNext 由前端从 total 推导 */
    public static <T> PageResult<T> of(Long total, List<T> records) {
        return new PageResult<>(total, null, records);
    }

    /** 跳过 COUNT 的构造：只带 hasNext，total 未知 */
    public static <T> PageResult<T> ofCursor(Boolean hasNext, List<T> records) {
        return new PageResult<>(null, hasNext, records);
    }

    /** 完整构造：total + hasNext 都有 */
    public static <T> PageResult<T> of(Long total, Boolean hasNext, List<T> records) {
        return new PageResult<>(total, hasNext, records);
    }

    public static <T> PageResult<T> empty() {
        return new PageResult<>(0L, Boolean.FALSE, Collections.emptyList());
    }
}

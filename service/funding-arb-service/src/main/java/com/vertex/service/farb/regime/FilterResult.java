package com.vertex.service.farb.regime;

import lombok.Data;

/**
 * 硬 filter 结果。qualified=true 才进 scorer；否则 rejectReason 记录原因供 event log。
 */
@Data
public class FilterResult {
    private final boolean qualified;
    private final String rejectReason;

    public static FilterResult pass() {
        return new FilterResult(true, null);
    }

    public static FilterResult reject(String reason) {
        return new FilterResult(false, reason);
    }
}

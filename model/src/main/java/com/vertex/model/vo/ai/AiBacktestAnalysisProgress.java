package com.vertex.model.vo.ai;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 回测 AI 批量分析进度（前端轮询查询此结构）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiBacktestAnalysisProgress implements Serializable {

    public enum Status {
        PENDING,    // 已入队待开始
        RUNNING,    // 处理中
        COMPLETED,  // 全部完成
        FAILED,     // 整体失败（少数失败不算）
        CANCELLED   // 已取消
    }

    private String cacheKey;
    private Long strategyId;
    private String strategyName;
    private Integer total;
    private Integer completed;
    private Integer failed;
    private Status status;
    private Long startedAt;
    private Long updatedAt;
    private Long completedAt;
    /** 整体失败时的错误信息（个别 trade 失败仅记录在该 trade 上）*/
    private String errorMessage;
}

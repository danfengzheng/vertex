package com.vertex.model.vo.strategy;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 信号清理"执行"结果。手动触发/定时任务共用返回。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SignalCleanupRunResultVO implements Serializable {

    /** 触发方式：MANUAL / SCHEDULED */
    private String trigger;
    /** 删除模式：SOFT / HARD */
    private String deleteMode;

    private long deletedNeutral;
    private long deletedDirectionalOrphan;
    private long deletedLinked;
    private long deletedTotal;

    /** RocksDB 级联清理条数（仅 HARD 模式；SOFT 模式为 0） */
    private long rocksdbAiAnalysisDeleted;

    private long startedAt;
    private long finishedAt;
    private long durationMs;

    /** 成功=null；失败=异常概要 */
    private String errorMessage;
}

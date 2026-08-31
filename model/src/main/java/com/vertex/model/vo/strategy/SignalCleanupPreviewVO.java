package com.vertex.model.vo.strategy;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 信号清理"预览"结果：不真删，只算数量。
 * <p>
 * 前端在弹 Modal 里展示"本次将删除 X 条，保留 Y 条"，让用户确认再执行。
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SignalCleanupPreviewVO implements Serializable {

    /** 当前 stg_signal 中 deleted=0 的总条数 */
    private long totalActive;

    /** 将删除的 NEUTRAL 条数（配置了 keepNeutralDays 才计算，否则 0）*/
    private long willDeleteNeutral;
    /** 将删除的 BUY/SELL 未关联订单条数 */
    private long willDeleteDirectionalOrphan;
    /** 将删除的 BUY/SELL 已关联订单条数 */
    private long willDeleteLinked;

    /** 三类合计 */
    private long willDeleteTotal;

    /** 清理后预计剩余 */
    private long afterCleanup;

    /** 生效的截止时间戳 ms（三条 cutoff 分别列出）*/
    private Long neutralCutoffMs;
    private Long directionalCutoffMs;
    private Long linkedCutoffMs;

    /** 保护期截止时间戳 ms（signal_time >= 此值绝对不删）*/
    private long protectCutoffMs;
}

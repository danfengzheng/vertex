package com.vertex.model.entity.strategy;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 信号清理动态配置（单行表，id=1）。
 * <p>
 * 分级 TTL + 保护期 + 软/硬删除模式，参见 V26_signal_cleanup.sql。
 * 修改后 5s 内定时任务生效。
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("signal_cleanup_config")
public class SignalCleanupConfig implements Serializable {

    /** 固定 1（单行表）*/
    @TableId(type = IdType.INPUT)
    private Long id;

    /** 总开关：0=关（默认，用户主动启用）；1=开 */
    private Integer enabled;

    // ─── 三级 TTL（NULL 或 <=0 = 不清理该类） ────────────────
    /** NEUTRAL 信号保留天数（默认 7 天） */
    private Integer keepNeutralDays;
    /** BUY/SELL 未关联订单保留天数（默认 30 天） */
    private Integer keepDirectionalDays;
    /** BUY/SELL 已关联订单保留天数（默认 365 天） */
    private Integer keepLinkedDays;

    /** 保护期：最近 N 天绝对不删（双保险，默认 3 天） */
    private Integer protectRecentDays;

    /** Spring cron，6 段：秒分时日月周（默认 "0 0 3 * * ?" 每天凌晨 3 点） */
    private String scheduleCron;
    /** SOFT / HARD */
    private String deleteMode;
    /** 每批 DELETE 条数上限（避免长事务） */
    private Integer batchSize;

    // ─── 最近一次运行结果（观测用） ──────────────────────────
    private LocalDateTime lastRunAt;
    private Long lastRunDeletedNeutral;
    private Long lastRunDeletedDirectional;
    private Long lastRunDeletedLinked;
    private Long lastRunDurationMs;
    private String lastRunError;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private Long updateBy;
}

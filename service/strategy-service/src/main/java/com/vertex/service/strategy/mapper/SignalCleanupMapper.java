package com.vertex.service.strategy.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 信号清理专用 Mapper —— 自定义 SQL，不走 MyBatis-Plus 的 @TableLogic 自动过滤。
 * <p>
 * 分三类操作：
 * <ol>
 *   <li>预览：只算数量，不删数据（用于 UI 展示"将删除 X 条"）</li>
 *   <li>软删除：UPDATE deleted=1（LIMIT N 分批）</li>
 *   <li>硬删除：DELETE 物理删除 + 返回 signalId 列表供 RocksDB 级联清理</li>
 * </ol>
 * </p>
 */
@Mapper
public interface SignalCleanupMapper {

    // ─── 预览计数（未软删的行）──────────────────────────────

    @Select("SELECT COUNT(*) FROM stg_signal " +
            "WHERE deleted = 0 " +
            "  AND signal_type = 'NEUTRAL' " +
            "  AND signal_time < #{cutoffMs}")
    long countNeutralOlderThan(@Param("cutoffMs") long cutoffMs);

    @Select("SELECT COUNT(*) FROM stg_signal s " +
            "LEFT JOIN trd_order o ON s.id = o.signal_id AND o.deleted = 0 " +
            "WHERE s.deleted = 0 " +
            "  AND s.signal_type IN ('BUY','SELL') " +
            "  AND o.id IS NULL " +
            "  AND s.signal_time < #{cutoffMs}")
    long countDirectionalOrphanOlderThan(@Param("cutoffMs") long cutoffMs);

    @Select("SELECT COUNT(*) FROM stg_signal s " +
            "INNER JOIN trd_order o ON s.id = o.signal_id AND o.deleted = 0 " +
            "WHERE s.deleted = 0 " +
            "  AND s.signal_type IN ('BUY','SELL') " +
            "  AND s.signal_time < #{cutoffMs}")
    long countLinkedOlderThan(@Param("cutoffMs") long cutoffMs);

    @Select("SELECT COUNT(*) FROM stg_signal WHERE deleted = 0")
    long countAllActive();

    // ─── 软删除（UPDATE deleted=1，返回 affected rows）──────

    @Update("UPDATE stg_signal SET deleted = 1, update_time = NOW() " +
            "WHERE deleted = 0 " +
            "  AND signal_type = 'NEUTRAL' " +
            "  AND signal_time < #{cutoffMs} " +
            "LIMIT #{limit}")
    int softDeleteNeutralBatch(@Param("cutoffMs") long cutoffMs, @Param("limit") int limit);

    @Update("UPDATE stg_signal SET deleted = 1, update_time = NOW() " +
            "WHERE deleted = 0 " +
            "  AND signal_type IN ('BUY','SELL') " +
            "  AND signal_time < #{cutoffMs} " +
            "  AND NOT EXISTS (SELECT 1 FROM trd_order o WHERE o.signal_id = stg_signal.id AND o.deleted = 0) " +
            "LIMIT #{limit}")
    int softDeleteDirectionalOrphanBatch(@Param("cutoffMs") long cutoffMs, @Param("limit") int limit);

    @Update("UPDATE stg_signal SET deleted = 1, update_time = NOW() " +
            "WHERE deleted = 0 " +
            "  AND signal_type IN ('BUY','SELL') " +
            "  AND signal_time < #{cutoffMs} " +
            "  AND EXISTS (SELECT 1 FROM trd_order o WHERE o.signal_id = stg_signal.id AND o.deleted = 0) " +
            "LIMIT #{limit}")
    int softDeleteLinkedBatch(@Param("cutoffMs") long cutoffMs, @Param("limit") int limit);

    // ─── 硬删除：先取 id 列表再物理删，返回删掉的 id ────────

    @Select("SELECT id FROM stg_signal " +
            "WHERE signal_type = 'NEUTRAL' " +
            "  AND signal_time < #{cutoffMs} " +
            "LIMIT #{limit}")
    List<Long> selectNeutralIdsOlderThan(@Param("cutoffMs") long cutoffMs, @Param("limit") int limit);

    @Select("SELECT s.id FROM stg_signal s " +
            "LEFT JOIN trd_order o ON s.id = o.signal_id AND o.deleted = 0 " +
            "WHERE s.signal_type IN ('BUY','SELL') " +
            "  AND s.signal_time < #{cutoffMs} " +
            "  AND o.id IS NULL " +
            "LIMIT #{limit}")
    List<Long> selectDirectionalOrphanIdsOlderThan(@Param("cutoffMs") long cutoffMs, @Param("limit") int limit);

    @Select("SELECT DISTINCT s.id FROM stg_signal s " +
            "INNER JOIN trd_order o ON s.id = o.signal_id AND o.deleted = 0 " +
            "WHERE s.signal_type IN ('BUY','SELL') " +
            "  AND s.signal_time < #{cutoffMs} " +
            "LIMIT #{limit}")
    List<Long> selectLinkedIdsOlderThan(@Param("cutoffMs") long cutoffMs, @Param("limit") int limit);

    /**
     * 按 id 列表物理删除。ids 为空返回 0。
     * 用 &lt;script&gt; 简单拼接 IN 子句；批次上限受调用侧控制（默认 1000）。
     */
    @Update("<script>" +
            "DELETE FROM stg_signal WHERE id IN " +
            "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>" +
            "</script>")
    int hardDeleteByIds(@Param("ids") List<Long> ids);
}

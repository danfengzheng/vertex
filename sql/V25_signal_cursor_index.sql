-- ============================================
-- V25_signal_cursor_index.sql
-- 给 stg_signal 加复合索引，支撑 cursor / keyset 分页深页查询：
--   WHERE (signal_time < :ct) OR (signal_time = :ct AND id < :ci)
--   ORDER BY signal_time DESC, id DESC
--   LIMIT N
-- 该 SQL 需要 (signal_time DESC, id DESC) 联合索引才能走索引扫描完成，
-- 否则会退化为 filesort，深游标性能塌陷。
-- 幂等：ADD INDEX IF NOT EXISTS 需 MySQL 8.0.29+，老版本手工判断。
-- ============================================

-- MySQL 索引默认升序，实际排序方向由查询决定；InnoDB 会用同一颗 B+Tree 反向遍历。
-- 联合索引第 1 列是 signal_time（主要过滤 + 排序），第 2 列 id（并列时的二级排序 + 唯一性）。
ALTER TABLE `stg_signal`
    ADD INDEX  `idx_signal_time_id` (`signal_time`, `id`);

-- 老 idx_signal_time 单列索引保留（其它查询可能用到）；后续若明确不需要可 DROP。

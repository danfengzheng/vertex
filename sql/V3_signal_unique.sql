-- ============================================================
-- V3: 防重复信号 & 防重复委托
-- ============================================================

-- 1. stg_signal 表：防止同一策略同一K线时间产生重复信号
-- 先清理已有重复数据（保留每组中 id 最小的一条）
DELETE s1 FROM stg_signal s1
INNER JOIN stg_signal s2
    ON s1.strategy_id = s2.strategy_id
    AND s1.signal_time = s2.signal_time
    AND s1.signal_type = s2.signal_type
    AND s1.id > s2.id;

-- 添加唯一索引
ALTER TABLE `stg_signal`
    ADD UNIQUE KEY `uk_strategy_signal_time_type` (`strategy_id`, `signal_time`, `signal_type`);

-- 2. trd_order 表：同一信号只能产生一笔委托（最后的兜底防线）
-- 先清理已有重复数据（保留每个 signal_id 中 id 最小的一条）
DELETE o1 FROM trd_order o1
INNER JOIN trd_order o2
    ON o1.signal_id = o2.signal_id
    AND o1.signal_id IS NOT NULL
    AND o1.id > o2.id;

ALTER TABLE `trd_order`
    ADD UNIQUE KEY `uk_signal_id` (`signal_id`);

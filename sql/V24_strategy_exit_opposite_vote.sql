-- ============================================
-- V24_strategy_exit_opposite_vote.sql
-- 给 stg_strategy 加一个字段：
--   exit_on_opposite_vote_ratio DECIMAL(4,3) NULL
--     用于 NEUTRAL 信号时的补充平仓判据。反向指标占比 ≥ 该值即平仓。
--     分母 = 总投票指标数（含 NEUTRAL），不算权重不算 FILTER。
--     NULL = 不启用（保持现状）；例：
--       0.25 → 4 个投票指标中 1 个反向就平（对多头持仓：sell_count/total ≥ 0.25 触发）
--       0.5  → 4 个中 2 个反向才平
--       1.0  → 全部反向才平（等同于反向信号 auto-close，实际无额外效果）
-- 幂等：可重复执行。
-- ============================================

ALTER TABLE `stg_strategy`
    ADD COLUMN  `exit_on_opposite_vote_ratio` DECIMAL(4,3) DEFAULT NULL
        COMMENT 'NEUTRAL 信号时反向指标占比≥该值即平仓；NULL=不启用；分母=总投票指标数，不算权重与 FILTER'
        AFTER `super_trend_sl_offset_pct`;

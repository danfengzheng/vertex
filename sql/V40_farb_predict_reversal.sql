-- ============================================================================
-- V40: 资金费套利 - 预测反转 v2（软/硬两档 exit）
-- ============================================================================

ALTER TABLE farb_strategy
    ADD COLUMN predict_reversal_enabled     TINYINT       NOT NULL DEFAULT 0
        COMMENT '预测反转 exit 开关（0=v1 reactive；1=启用 v2 软硬两档）',
    ADD COLUMN predict_hard_score           DECIMAL(4,2)  NOT NULL DEFAULT 0.30
        COMMENT '硬阈值：healthScore < hard → 立即平仓（PREDICT_REVERSAL）',
    ADD COLUMN predict_soft_score           DECIMAL(4,2)  NOT NULL DEFAULT 0.60
        COMMENT '软阈值：healthScore < soft → 停开新仓 + 收紧 TP/regime break',
    ADD COLUMN predict_break_count_tightened INT          NOT NULL DEFAULT 2
        COMMENT '软触发后 exit_regime_break_count 收紧到此值（应 <= 原值）',
    ADD COLUMN predict_tp_tightened_pct     DECIMAL(6,2)  NOT NULL DEFAULT 0.30
        COMMENT '软触发后 TP 收紧到此百分比（应 <= exit_take_profit_pct）',
    ADD COLUMN predict_pause_new_min        INT           NOT NULL DEFAULT 60
        COMMENT '软触发后 symbol 停开新仓的时长（分钟）';

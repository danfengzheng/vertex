-- ============================================================================
-- V42: 资金费套利 - 强平保护
-- ============================================================================

ALTER TABLE farb_strategy
    ADD COLUMN liquidation_protection_enabled TINYINT NOT NULL DEFAULT 1
        COMMENT '是否启用强平保护（perp 反向大幅波动时主动平仓）',
    ADD COLUMN liquidation_safe_move_pct      DECIMAL(6,2) NULL DEFAULT NULL
        COMMENT '主动平仓阈值(%)，为 NULL 时自动 = 0.7 × (1/leverage)';

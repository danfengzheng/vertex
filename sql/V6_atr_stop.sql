-- V6: ATR止损支持
-- 在策略表中增加 ATR 止损倍数字段，设置后优先于固定止损百分比

ALTER TABLE stg_strategy
    ADD COLUMN atr_stop_multiplier DECIMAL(10, 4) NULL COMMENT 'ATR止损倍数（如 2.0），设置后优先于固定止损百分比(stop_loss_pct)';

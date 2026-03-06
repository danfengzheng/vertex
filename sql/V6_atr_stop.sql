-- V6: ATR止损 + ATR止盈 支持
-- 在策略表中增加 ATR 止损/止盈倍数字段，设置后分别优先于固定百分比配置

ALTER TABLE stg_strategy
    ADD COLUMN atr_stop_multiplier        DECIMAL(10, 4) NULL COMMENT 'ATR止损倍数（如 2.0），设置后优先于固定止损百分比(stop_loss_pct)',
    ADD COLUMN atr_take_profit_multiplier DECIMAL(10, 4) NULL COMMENT 'ATR止盈倍数（如 3.0），设置后优先于固定止盈百分比(take_profit_pct)';

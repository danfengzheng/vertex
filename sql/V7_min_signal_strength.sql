-- V7: 策略最低信号强度门槛
-- 信号强度低于此值时，即使 autoTrade=1 也不触发实盘委托
-- 未配置（NULL）时，代码默认使用 60

ALTER TABLE stg_strategy
    ADD COLUMN min_signal_strength INT NULL COMMENT '自动交易最低信号强度门槛(0-100)，NULL时默认60' AFTER auto_trade;

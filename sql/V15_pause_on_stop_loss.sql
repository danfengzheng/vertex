-- V15: 止损熔断开关（替换 V14 的百分比阈值字段）
-- 任意一笔止损触发且实际亏损时，暂停该策略开仓 24 小时

ALTER TABLE stg_strategy
    DROP COLUMN daily_loss_limit_pct,
    ADD COLUMN pause_on_stop_loss TINYINT(1) NOT NULL DEFAULT 0
        COMMENT '止损熔断开关（1=启用），止损触发且亏损时暂停开仓 24 小时';

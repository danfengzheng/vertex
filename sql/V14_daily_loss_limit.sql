-- V14: 日亏损限制（日止损熔断）
-- 策略当日累计亏损超过配置比例后，暂停交易 24 小时
-- trading_paused_until 持久化到数据库，重启后仍然生效

ALTER TABLE stg_strategy
    ADD COLUMN daily_loss_limit_pct DECIMAL(10,4) DEFAULT NULL
        COMMENT '日亏损限制百分比（如 5.0 = 5%），当日累计亏损超过此比例后暂停交易 24 小时，null=不启用',
    ADD COLUMN trading_paused_until DATETIME DEFAULT NULL
        COMMENT '交易暂停截止时间（UTC），在此时间之前不允许开新仓，null=未暂停';

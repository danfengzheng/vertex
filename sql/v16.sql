ALTER TABLE stg_strategy
    ADD COLUMN super_trend_sl_offset_pct DECIMAL(10,4) NULL
    COMMENT 'SuperTrend止损偏移百分比(如1.0=1%)，null或<=0表示不启用';

ALTER TABLE trd_position
    ADD COLUMN super_trend_stop_loss DECIMAL(20,8) NULL
    COMMENT 'SuperTrend动态止损价，由策略引擎每根K线更新，null=未启用';
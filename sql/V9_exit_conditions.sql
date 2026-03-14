-- V9: 独立出场指标配置 + 时间止损
-- 新增出场指标配置字段（独立于入场，同 indicatorConfigs 格式）
-- 新增最大持仓K线计数字段（时间止损兜底）

ALTER TABLE stg_strategy
    ADD COLUMN exit_indicator_configs TEXT COMMENT '出场指标配置JSON，同indicatorConfigs格式，为空时不启用指标出场',
    ADD COLUMN max_holding_bars       INT  COMMENT '最大持仓K线根数，超过后强制平仓，NULL=不限';

ALTER TABLE trd_position
    ADD COLUMN open_bar_count INT NOT NULL DEFAULT 0 COMMENT '开仓后经历的K线根数（时间止损用）';

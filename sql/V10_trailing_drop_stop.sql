-- V10: 峰值回撤止损（Trailing Drop Stop）
-- 开仓后追踪最优价格，当从峰值回撤超过指定百分比时触发止损

ALTER TABLE stg_strategy
    ADD COLUMN trailing_drop_pct DECIMAL(10, 4) DEFAULT NULL COMMENT '峰值回撤止损百分比（如 5.0 = 5%），从最高价(多)/最低价(空)回撤超过此值时止损';

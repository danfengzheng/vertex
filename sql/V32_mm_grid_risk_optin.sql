-- ============================================
-- V32_mm_grid_risk_optin.sql
-- 修复"隐藏坑"：账户级 pnl 自动平仓改为 opt-in
--
-- 背景：
--   V29 里 account_emergency_pnl_pct DEFAULT 5.0，AccountRiskAggregator 每 1min
--   评估账户浮亏 > 5% 就自动 emergencyStop 全账户策略。用户不知情就中招。
--
-- 修复：
--   新增 risk_auto_stop_enabled 开关，默认 0（关）。
--   只有显式打开，account_emergency_pnl_pct 才生效。
--   MARGIN_CALL 事件仍然强制触发（真爆仓警报，不能关）。
-- ============================================

SET @col_exists := (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME   = 'grid_strategy'
      AND COLUMN_NAME  = 'risk_auto_stop_enabled'
);
SET @sql := IF(@col_exists = 0,
    'ALTER TABLE `grid_strategy` ADD COLUMN `risk_auto_stop_enabled` TINYINT NOT NULL DEFAULT 0
        COMMENT ''账户级 pnl 阈值自动 emergencyStop 开关；0=关(默认), 1=开;
                 关闭时 account_emergency_pnl_pct 不触发;
                 币安 MARGIN_CALL 事件仍强制触发。''
     AFTER `account_margin_usage_pct`',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

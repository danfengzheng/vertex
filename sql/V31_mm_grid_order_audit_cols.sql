-- ============================================
-- V31_mm_grid_order_audit_cols.sql
-- 修复：grid_order 缺 create_by / update_by 列
-- 原因：GridOrder extends BaseEntity（含 createBy/updateBy），
--       V29 建表时漏了这两列 → MyBatis-Plus SELECT * 报
--       "Unknown column 'create_by' in 'field list'"。
--
-- 幂等：通过 INFORMATION_SCHEMA 判断列是否已存在。
-- ============================================

-- grid_order.create_by
SET @col_exists := (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME   = 'grid_order'
      AND COLUMN_NAME  = 'create_by'
);
SET @sql := IF(@col_exists = 0,
    'ALTER TABLE `grid_order` ADD COLUMN `create_by` BIGINT NULL AFTER `reject_reason`',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- grid_order.update_by
SET @col_exists := (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME   = 'grid_order'
      AND COLUMN_NAME  = 'update_by'
);
SET @sql := IF(@col_exists = 0,
    'ALTER TABLE `grid_order` ADD COLUMN `update_by` BIGINT NULL AFTER `create_time`',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

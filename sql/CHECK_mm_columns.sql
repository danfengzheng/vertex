-- ============================================
-- 诊断 mm 模块列缺失情况
-- 用法：mysql <你的库> < CHECK_mm_columns.sql
-- ============================================

SELECT '========== grid_strategy paper 列（V30）==========' AS msg;
SELECT COLUMN_NAME
FROM INFORMATION_SCHEMA.COLUMNS
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME   = 'grid_strategy'
  AND COLUMN_NAME IN ('execution_mode', 'paper_queue_loss_factor',
                      'paper_gtx_reject_rate', 'paper_smart_pricing_enabled',
                      'paper_min_liquidity_qty');
-- 应返回 5 行；少于 5 行说明 V30 没跑

SELECT '========== grid_order 审计列（V31）==========' AS msg;
SELECT COLUMN_NAME
FROM INFORMATION_SCHEMA.COLUMNS
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME   = 'grid_order'
  AND COLUMN_NAME IN ('create_by', 'update_by', 'create_time', 'update_time', 'deleted');
-- 应返回 5 行；少了说明 V29+V31 都没跑全

SELECT '========== grid_strategy 审计列（V29）==========' AS msg;
SELECT COLUMN_NAME
FROM INFORMATION_SCHEMA.COLUMNS
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME   = 'grid_strategy'
  AND COLUMN_NAME IN ('create_by', 'update_by', 'create_time', 'update_time', 'deleted');
-- 应返回 5 行

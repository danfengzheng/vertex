-- ============================================
-- V18: 个人设置表（per-user 偏好配置）
-- 当前字段：
--   max_trade_capital — 单笔开仓最大使用资金（U/USDT），<=0 或 NULL = 不限制
-- 未来字段可在此表追加（如默认杠杆、默认 K 线周期等）。
-- ============================================

CREATE TABLE IF NOT EXISTS `sys_user_setting` (
    `id`                BIGINT          NOT NULL COMMENT '主键',
    `user_id`           BIGINT          NOT NULL COMMENT '用户ID',
    `max_trade_capital` DECIMAL(20,8)   DEFAULT NULL
        COMMENT '单笔开仓最大使用资金（U/USDT），<=0 或 NULL = 不限制',
    `create_by`         BIGINT          DEFAULT NULL COMMENT '创建者',
    `create_time`       DATETIME        DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_by`         BIGINT          DEFAULT NULL COMMENT '更新者',
    `update_time`       DATETIME        DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`           TINYINT         DEFAULT 0 COMMENT '删除标记 0-正常 1-删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_id` (`user_id`),
    KEY `idx_deleted` (`deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户个人设置表';

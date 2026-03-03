-- ============================================
-- V4: 用户通知配置表
-- 每个用户可为不同渠道（当前仅 TELEGRAM）单独配置通知开关和目标 chat_id
-- ============================================

CREATE TABLE IF NOT EXISTS `sys_notify_config` (
    `id`          BIGINT        NOT NULL COMMENT '主键',
    `user_id`     BIGINT        NOT NULL COMMENT '用户ID',
    `channel`     VARCHAR(20)   NOT NULL DEFAULT 'TELEGRAM' COMMENT '通知渠道 TELEGRAM',
    `chat_id`     VARCHAR(100)  DEFAULT NULL COMMENT 'Telegram Chat ID（个人或群组）',
    `enabled`     TINYINT       DEFAULT 1 COMMENT '是否启用 0-关闭 1-开启',
    `create_by`   BIGINT        DEFAULT NULL COMMENT '创建者',
    `create_time` DATETIME      DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_by`   BIGINT        DEFAULT NULL COMMENT '更新者',
    `update_time` DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`     TINYINT       DEFAULT 0 COMMENT '删除标记 0-正常 1-删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_channel` (`user_id`, `channel`),
    KEY `idx_user_id` (`user_id`),
    KEY `idx_deleted` (`deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户通知配置表';

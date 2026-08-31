-- ============================================
-- V26_signal_cleanup.sql
-- 信号数据清理：单行配置表 signal_cleanup_config + 分级 TTL + 保护期。
--
-- 分级 TTL（三条独立保留期，NULL/-1 表示不清理该类）：
--   keep_neutral_days       —— NEUTRAL 信号（噪音，默认 7 天）
--   keep_directional_days   —— BUY / SELL 且未关联订单（默认 30 天）
--   keep_linked_days        —— BUY / SELL 且已关联订单（默认 365 天）
--
-- 保护期（双保险，最近 N 天绝对不删）：
--   protect_recent_days     —— 默认 3 天
--
-- delete_mode：
--   SOFT —— 只标 deleted=1，MyBatis-Plus @TableLogic 自动过滤（默认，最安全）
--   HARD —— 物理 DELETE，同时清 RocksDB (SignalStore + AiAnalysisStore)
--
-- 幂等：可重复执行。
-- ============================================

-- 1) 建表：单行配置（id=1）
CREATE TABLE IF NOT EXISTS `signal_cleanup_config` (
    `id`                       BIGINT      NOT NULL PRIMARY KEY COMMENT '固定 1，全局唯一配置行',
    `enabled`                  TINYINT     NOT NULL DEFAULT 0 COMMENT '总开关（默认关，用户主动启用）',

    `keep_neutral_days`        INT         DEFAULT 7    COMMENT 'NEUTRAL 保留天数；NULL 或 -1=不清理',
    `keep_directional_days`    INT         DEFAULT 30   COMMENT 'BUY/SELL 未关联订单保留天数；NULL 或 -1=不清理',
    `keep_linked_days`         INT         DEFAULT 365  COMMENT 'BUY/SELL 已关联订单保留天数；NULL 或 -1=不清理',

    `protect_recent_days`      INT         NOT NULL DEFAULT 3 COMMENT '双保险：最近 N 天绝对不删',

    `schedule_cron`            VARCHAR(64) NOT NULL DEFAULT '0 0 3 * * ?' COMMENT 'Spring cron，6 段：秒分时日月周',
    `delete_mode`              VARCHAR(16) NOT NULL DEFAULT 'SOFT' COMMENT 'SOFT / HARD',
    `batch_size`               INT         NOT NULL DEFAULT 1000 COMMENT '每批 DELETE 条数上限',

    `last_run_at`              DATETIME    DEFAULT NULL COMMENT '最近一次运行开始时间',
    `last_run_deleted_neutral` BIGINT      DEFAULT 0    COMMENT '最近一次删除 NEUTRAL 条数',
    `last_run_deleted_directional` BIGINT  DEFAULT 0    COMMENT '最近一次删除 BUY/SELL 未关联订单条数',
    `last_run_deleted_linked`  BIGINT      DEFAULT 0    COMMENT '最近一次删除 BUY/SELL 已关联订单条数',
    `last_run_duration_ms`     BIGINT      DEFAULT 0    COMMENT '最近一次耗时 ms',
    `last_run_error`           VARCHAR(500) DEFAULT NULL COMMENT '最近一次错误信息（成功=NULL）',

    `create_time`              DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`              DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `update_by`                BIGINT      DEFAULT NULL COMMENT '最后修改用户'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='信号清理动态配置（单行）';

-- 2) 插入默认行（幂等）—— 默认 enabled=0，用户须主动开启
INSERT INTO `signal_cleanup_config`
    (id, enabled, keep_neutral_days, keep_directional_days, keep_linked_days,
     protect_recent_days, schedule_cron, delete_mode, batch_size)
VALUES
    (1, 0, 7, 30, 365, 3, '0 0 3 * * ?', 'SOFT', 1000)
ON DUPLICATE KEY UPDATE update_time = update_time;

-- 3) sys_menu：在「策略管理」组下加「信号清理」（id=33，parent=3）
INSERT INTO `sys_menu`
    (id, parent_id, name, i18n_key, path, component, icon, `type`, permission, sort, status, deleted, create_time, update_time)
VALUES
    (33, 3, '信号清理', 'text.strategy.cleanupTitle', '/strategy/cleanup',
     'SignalCleanup', 'DeleteOutlined', 1, 'signal:cleanup', 3, 1, 0, NOW(), NOW())
ON DUPLICATE KEY UPDATE
    parent_id   = VALUES(parent_id),
    name        = VALUES(name),
    i18n_key    = VALUES(i18n_key),
    path        = VALUES(path),
    component   = VALUES(component),
    icon        = VALUES(icon),
    `type`      = VALUES(`type`),
    permission  = VALUES(permission),
    sort        = VALUES(sort),
    status      = VALUES(status),
    deleted     = 0,
    update_time = NOW();

-- 4) 绑定到 administrator 角色
INSERT IGNORE INTO `sys_role_menu`
    (id, role_id, menu_id, create_by, create_time, update_by, update_time, deleted)
SELECT
    9000000000 + ROW_NUMBER() OVER (ORDER BY r.id) + 260 AS id,
    r.id AS role_id,
    33   AS menu_id,
    1, NOW(), 1, NOW(), 0
FROM `sys_role` r
WHERE r.code = 'administrator' AND r.deleted = 0;

-- 5) 兼容旧版本：软删除恢复
UPDATE `sys_menu` SET deleted = 0, update_time = NOW() WHERE id = 33 AND deleted = 1;
UPDATE `sys_role_menu` SET deleted = 0, update_time = NOW() WHERE menu_id = 33 AND deleted = 1;

-- 6) 建索引：加速按 signal_time / signal_type 过滤（避免 DELETE 全表扫）
--    idx_signal_time_id 已在 V25 建过，这里只补类型索引
--    如果表已有对应索引，MySQL 8.0.29+ IF NOT EXISTS 幂等；老版本会报重复索引可忽略
ALTER TABLE `stg_signal`
    ADD INDEX `idx_signal_type_time` (`signal_type`, `signal_time`);

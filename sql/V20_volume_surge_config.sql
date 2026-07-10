-- ============================================
-- V20_volume_surge_config.sql
-- 币安现货「成交量暴增」扫描器的动态配置表 + 前端菜单
-- 用户可以在 UI 上直接改配置 / 打开关闭，无需重启服务。
-- 幂等：可重复执行。
-- ============================================

-- 1) 建表：单行配置（id=1）
CREATE TABLE IF NOT EXISTS `volume_surge_config` (
    `id` BIGINT NOT NULL PRIMARY KEY COMMENT '固定 1，全局唯一配置行',
    `enabled` TINYINT NOT NULL DEFAULT 0 COMMENT '总开关 0=关 1=开（可热切换）',
    `scan_interval_minutes` INT NOT NULL DEFAULT 15 COMMENT '扫描间隔（分钟）',
    `quote_currency` VARCHAR(10) NOT NULL DEFAULT 'USDT' COMMENT '计价币',
    `surge_ratio_threshold` DECIMAL(10,2) NOT NULL DEFAULT 10.00 COMMENT '主判据：暴增倍数',
    `min_price_change_1h_pct` DECIMAL(10,2) NOT NULL DEFAULT 2.00 COMMENT '辅判据：1H 价格变化%',
    `baseline_hours` INT NOT NULL DEFAULT 24 COMMENT 'baseline 窗口 1H 根数',
    `min_baseline_median_usdt` DECIMAL(20,2) NOT NULL DEFAULT 5000.00 COMMENT 'baseline 中位数下限（死币过滤）',
    `min_24h_quote_volume_usdt` DECIMAL(20,2) NOT NULL DEFAULT 50000.00 COMMENT '24h 成交额下限',
    `max_24h_quote_volume_usdt` DECIMAL(20,2) NOT NULL DEFAULT 10000000.00 COMMENT '24h 成交额上限（挡主流）',
    `prefilter_min_abs_24h_price_change_pct` DECIMAL(10,2) NOT NULL DEFAULT 3.00 COMMENT '预筛 24h 波动阈值',
    `exclude_days_since_listing` INT NOT NULL DEFAULT 7 COMMENT '排除新上币天数',
    `cooldown_hours` INT NOT NULL DEFAULT 6 COMMENT '同 symbol 告警冷却小时数',
    `alert_directions` VARCHAR(8) NOT NULL DEFAULT 'BOTH' COMMENT 'UP/DOWN/BOTH',
    `symbol_blacklist` TEXT DEFAULT NULL COMMENT '逗号分隔的黑名单 symbol',
    `symbol_whitelist` TEXT DEFAULT NULL COMMENT '逗号分隔的白名单 symbol；空=全扫',
    `telegram_enabled` TINYINT NOT NULL DEFAULT 0 COMMENT 'Telegram 推送开关',
    `telegram_bot_token` VARCHAR(200) DEFAULT NULL COMMENT 'Telegram Bot Token',
    `telegram_chat_id` VARCHAR(50) DEFAULT NULL COMMENT 'Telegram Chat ID',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `update_by` BIGINT DEFAULT NULL COMMENT '最后修改用户'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='币安成交量暴增扫描器动态配置（单行）';

-- 2) 插入默认行（幂等）
INSERT INTO `volume_surge_config` (id, enabled, scan_interval_minutes, quote_currency,
    surge_ratio_threshold, min_price_change_1h_pct, baseline_hours,
    min_baseline_median_usdt, min_24h_quote_volume_usdt, max_24h_quote_volume_usdt,
    prefilter_min_abs_24h_price_change_pct, exclude_days_since_listing,
    cooldown_hours, alert_directions,
    symbol_blacklist, symbol_whitelist,
    telegram_enabled, telegram_bot_token, telegram_chat_id)
VALUES (1, 0, 15, 'USDT',
    10.00, 2.00, 24,
    5000.00, 50000.00, 10000000.00,
    3.00, 7,
    6, 'BOTH',
    'USDCUSDT,FDUSDT,TUSDUSDT,DAIUSDT', NULL,
    0, NULL, NULL)
ON DUPLICATE KEY UPDATE update_time = update_time;

-- 3) sys_menu：把「行情管理 → 成交量暴增」加进去（id=23，parent=2）
INSERT INTO `sys_menu`
    (id, parent_id, name, i18n_key, path, component, icon, `type`, permission, sort, status, deleted, create_time, update_time)
VALUES
    (23, 2, '成交量暴增', 'text.quote.volumeSurgeTitle', '/quote/volume-surge',
     'VolumeSurgeConfig', 'ThunderboltOutlined', 1, 'quote:volume-surge', 3, 1, 0, NOW(), NOW())
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

-- 4) 自动绑定 code='administrator' 角色
INSERT IGNORE INTO `sys_role_menu`
    (id, role_id, menu_id, create_by, create_time, update_by, update_time, deleted)
SELECT
    9000000000 + ROW_NUMBER() OVER (ORDER BY r.id, m.id) + 100 AS id,
    r.id AS role_id,
    m.id AS menu_id,
    1, NOW(), 1, NOW(), 0
FROM `sys_role` r
CROSS JOIN (SELECT 23 AS id) m
WHERE r.code = 'administrator' AND r.deleted = 0;

-- 5) 兼容旧版本：软删除恢复
UPDATE `sys_menu` SET deleted = 0, update_time = NOW() WHERE id = 23 AND deleted = 1;
UPDATE `sys_role_menu` SET deleted = 0, update_time = NOW() WHERE menu_id = 23 AND deleted = 1;

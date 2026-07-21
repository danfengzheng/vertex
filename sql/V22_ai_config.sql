-- ============================================
-- V22_ai_config.sql
-- 把 vertex.ai.* 的业务配置从 yaml 搬到 DB（单行 ai_config 表）。
-- yaml 只保留 bean 级安装开关 + 线程池参数（重启期读的）；
-- 所有可热切换的 provider / model / api-key / language 走这张表。
-- 幂等：可重复执行。
-- ============================================

-- 1) 建表：单行配置（id=1）
CREATE TABLE IF NOT EXISTS `ai_config` (
    `id` BIGINT NOT NULL PRIMARY KEY COMMENT '固定 1，全局唯一配置行',
    `enabled` TINYINT NOT NULL DEFAULT 0 COMMENT '总开关（可热切换）',

    -- ── Provider 选择与共享参数 ──
    `provider` VARCHAR(16) NOT NULL DEFAULT 'gemini' COMMENT 'gemini / deepseek',
    `language` VARCHAR(16) NOT NULL DEFAULT 'zh-CN' COMMENT '自由文本输出语言（BCP-47）',

    -- ── Gemini 参数 ──
    `gemini_api_key` VARCHAR(200) DEFAULT NULL COMMENT 'Google AI Studio 申请',
    `gemini_model` VARCHAR(64) NOT NULL DEFAULT 'gemini-2.0-flash' COMMENT 'gemini-2.0-flash / gemini-2.5-pro 等',
    `gemini_base_url` VARCHAR(200) NOT NULL DEFAULT 'https://generativelanguage.googleapis.com',
    `gemini_timeout_seconds` INT NOT NULL DEFAULT 30,
    `gemini_max_retry` INT NOT NULL DEFAULT 2,

    -- ── DeepSeek 参数 ──
    `deepseek_api_key` VARCHAR(200) DEFAULT NULL COMMENT 'platform.deepseek.com 申请',
    `deepseek_model` VARCHAR(64) NOT NULL DEFAULT 'deepseek-chat' COMMENT 'deepseek-chat / deepseek-reasoner',
    `deepseek_base_url` VARCHAR(200) NOT NULL DEFAULT 'https://api.deepseek.com',
    `deepseek_timeout_seconds` INT NOT NULL DEFAULT 60,
    `deepseek_max_retry` INT NOT NULL DEFAULT 2,

    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `update_by` BIGINT DEFAULT NULL COMMENT '最后修改用户'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='AI 模块动态配置（单行）';

-- 2) 插入默认行（幂等）
INSERT INTO `ai_config`
    (id, enabled, provider, language,
     gemini_api_key, gemini_model, gemini_base_url, gemini_timeout_seconds, gemini_max_retry,
     deepseek_api_key, deepseek_model, deepseek_base_url, deepseek_timeout_seconds, deepseek_max_retry)
VALUES
    (1, 0, 'gemini', 'zh-CN',
     NULL, 'gemini-2.0-flash', 'https://generativelanguage.googleapis.com', 30, 2,
     NULL, 'deepseek-chat', 'https://api.deepseek.com', 60, 2)
ON DUPLICATE KEY UPDATE update_time = update_time;

-- 3) sys_menu：在「AI 分析」组下加「AI 配置」（id=63，parent=6）
INSERT INTO `sys_menu`
    (id, parent_id, name, i18n_key, path, component, icon, `type`, permission, sort, status, deleted, create_time, update_time)
VALUES
    (63, 6, 'AI 配置', 'text.ai.configTitle', '/ai/config',
     'AiConfig', 'SettingOutlined', 1, 'ai:config', 3, 1, 0, NOW(), NOW())
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
    9000000000 + ROW_NUMBER() OVER (ORDER BY r.id) + 200 AS id,
    r.id AS role_id,
    63   AS menu_id,
    1, NOW(), 1, NOW(), 0
FROM `sys_role` r
WHERE r.code = 'administrator' AND r.deleted = 0;

-- 5) 兼容旧版本：软删除恢复
UPDATE `sys_menu` SET deleted = 0, update_time = NOW() WHERE id = 63 AND deleted = 1;
UPDATE `sys_role_menu` SET deleted = 0, update_time = NOW() WHERE menu_id = 63 AND deleted = 1;

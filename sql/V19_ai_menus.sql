-- ============================================
-- V19_ai_menus.sql
-- 将 AI 分析（仪表盘 / 运行状态）两个菜单纳入 sys_menu 权限管理。
-- 与 init_menus.sql / update_menu_permissions.sql 风格一致：
--   * id  固定整数（与 init_menus.sql 的 1~52、60 保持同一段）
--   * 顶级目录 id=6 (AI 分析)，子菜单 id=61/62
--   * 权限标识 ai:dashboard / ai:status（与后端 @RequiresPermission 一致）
-- 幂等：可重复执行；sys_menu 用 ON DUPLICATE KEY UPDATE，
--       sys_role_menu 用 INSERT IGNORE + uk_role_menu 唯一约束。
-- ============================================

-- 1) 新增 / 更新 AI 相关菜单
INSERT INTO `sys_menu`
  (id, parent_id, name, i18n_key, path, component, icon, `type`, permission, sort, status, deleted, create_time, update_time)
VALUES
  (6,  0, 'AI 分析',      'text.ai.title',          NULL,            NULL,          'RobotOutlined',    0, NULL,           6, 1, 0, NOW(), NOW()),
  (61, 6, 'AI 仪表盘',    'text.ai.dashboardTitle', '/ai/dashboard', 'AiDashboard', 'RobotOutlined',    1, 'ai:dashboard', 1, 1, 0, NOW(), NOW()),
  (62, 6, 'AI 运行状态',  'text.ai.statusTitle',    '/ai/status',    'AiStatus',    'MonitorOutlined',  1, 'ai:status',    2, 1, 0, NOW(), NOW())
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

-- 2) 自动绑定到 code='administrator' 的所有角色，避免管理员上线后看不到菜单。
--    其它角色（如自定义角色）请在「系统管理 → 角色管理」里手动勾选。
--    ID 用 9000000000 + ROW_NUMBER 起步，远低于业务雪花 ID（~2028xxxxxxxxxxxxxxx），不会冲突。
INSERT IGNORE INTO `sys_role_menu`
  (id, role_id, menu_id, create_by, create_time, update_by, update_time, deleted)
SELECT
  9000000000 + ROW_NUMBER() OVER (ORDER BY r.id, m.id) AS id,
  r.id   AS role_id,
  m.id   AS menu_id,
  1, NOW(), 1, NOW(), 0
FROM `sys_role` r
CROSS JOIN (
  SELECT 6  AS id UNION ALL
  SELECT 61          UNION ALL
  SELECT 62
) m
WHERE r.code = 'administrator'
  AND r.deleted = 0;

-- 3) （兼容旧版本：把脚本之前手动加过的、deleted=1 的同 ID 软删除行恢复）
UPDATE `sys_menu`
SET deleted = 0, update_time = NOW()
WHERE id IN (6, 61, 62) AND deleted = 1;

UPDATE `sys_role_menu`
SET deleted = 0, update_time = NOW()
WHERE menu_id IN (6, 61, 62) AND deleted = 1;

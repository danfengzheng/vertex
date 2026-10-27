-- ============================================================================
-- V39: 资金费套利 - 回测子菜单
-- ============================================================================

INSERT IGNORE INTO sys_menu
    (id, parent_id, name, i18n_key, path, component, icon, type, permission, sort, status, deleted, create_time, update_time)
VALUES
    (37, 8, '回测', 'text.farb.backtest', '/farb/backtest',
     'FarbBacktest', 'ThunderboltOutlined', 1, 'farb:strategy', 2, 1, 0, NOW(), NOW());

-- ============================================
-- 初始化系统菜单数据（与 MainLayout.tsx menuItems 保持一致）
-- 幂等脚本：重复执行不会报错
-- ============================================

INSERT INTO `sys_menu` (id, parent_id, name, i18n_key, path, component, icon, `type`, sort, status, deleted, create_time, update_time)
VALUES
-- ── 一级目录（type=0）────────────────────────────────────────────────────
(1,  0, '系统管理', 'text.system.title',   NULL,               NULL,                 'AppstoreOutlined',    0, 1,  1, 0, NOW(), NOW()),
(2,  0, '行情管理', 'text.quote.title',    NULL,               NULL,                 'StockOutlined',       0, 2,  1, 0, NOW(), NOW()),
(3,  0, '策略管理', 'text.strategy.title', NULL,               NULL,                 'FundOutlined',        0, 3,  1, 0, NOW(), NOW()),
(4,  0, '交易管理', 'text.trading.title',  NULL,               NULL,                 'SwapOutlined',        0, 4,  1, 0, NOW(), NOW()),
(5,  0, '链上分析', 'text.chain.title',    NULL,               NULL,                 'NodeExpandOutlined',  0, 5,  1, 0, NOW(), NOW()),

-- ── 系统管理子菜单（type=1）──────────────────────────────────────────────
(11, 1, '用户管理', 'text.system.user',    '/user',            'UserManagement',     'UserOutlined',        1, 1, 1, 0, NOW(), NOW()),
(12, 1, '菜单管理', 'text.system.menu',    '/menu',            'MenuManagement',     'MenuOutlined',        1, 2, 1, 0, NOW(), NOW()),
(13, 1, '角色管理', 'text.system.role',    '/role',            'RoleManagement',     'SafetyOutlined',      1, 3, 1, 0, NOW(), NOW()),

-- ── 行情管理子菜单 ──────────────────────────────────────────────────────
(21, 2, '行情源',  'text.quote.sourceTitle', '/quote/source',  'QuoteSource',        'ApiOutlined',         1, 1, 1, 0, NOW(), NOW()),
(22, 2, 'K线数据', 'text.quote.klineTitle',  '/quote/kline',   'KlineManagement',    'LineChartOutlined',   1, 2, 1, 0, NOW(), NOW()),

-- ── 策略管理子菜单 ──────────────────────────────────────────────────────
(31, 3, '策略配置', 'text.strategy.configTitle',  '/strategy/config',   'StrategyManagement', 'SettingOutlined',     1, 1, 1, 0, NOW(), NOW()),
(32, 3, '信号监控', 'text.strategy.signalTitle',  '/strategy/signals',  'SignalManagement',   'ThunderboltOutlined', 1, 2, 1, 0, NOW(), NOW()),

-- ── 交易管理子菜单 ──────────────────────────────────────────────────────
(41, 4, '交易账户', 'text.trading.accountTitle',    '/trading/accounts',  'AccountManagement',  'BankOutlined',        1, 1, 1, 0, NOW(), NOW()),
(42, 4, '订单记录', 'text.trading.orderTitle',      '/trading/orders',    'OrderManagement',    'OrderedListOutlined', 1, 2, 1, 0, NOW(), NOW()),
(43, 4, '持仓管理', 'text.trading.positionTitle',   '/trading/positions', 'PositionManagement', 'DashboardOutlined',   1, 3, 1, 0, NOW(), NOW()),
(44, 4, '盈亏分析', 'text.trading.pnlAnalysisTitle','/trading/pnl',       'PnlAnalysis',        'PieChartOutlined',    1, 4, 1, 0, NOW(), NOW()),
(45, 4, '币对管理', 'text.trading.symbolTitle',    '/trading/symbols',   'SymbolManagement',   'DatabaseOutlined',    1, 5, 1, 0, NOW(), NOW()),

-- ── 链上分析子菜单 ──────────────────────────────────────────────────────
(51, 5, '代币列表', 'text.chain.tokenListTitle', '/chain/tokens', 'TokenManagement',  'FundOutlined', 1, 1, 1, 0, NOW(), NOW()),
(52, 5, '预警规则', 'text.chain.alertRuleTitle', '/chain/alerts', 'AlertManagement',  'BellOutlined', 1, 2, 1, 0, NOW(), NOW()),

-- ── 顶级叶子菜单 ────────────────────────────────────────────────────────
(60, 0, '策略指南', 'text.guide.title', '/guide/strategy', 'StrategyGuide', 'BookOutlined', 1, 10, 1, 0, NOW(), NOW())

ON DUPLICATE KEY UPDATE
  name        = VALUES(name),
  i18n_key    = VALUES(i18n_key),
  path        = VALUES(path),
  component   = VALUES(component),
  icon        = VALUES(icon),
  `type`      = VALUES(`type`),
  sort        = VALUES(sort),
  status      = VALUES(status),
  update_time = NOW();

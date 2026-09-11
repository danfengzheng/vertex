-- ============================================
-- V29_mm_grid_module.sql
-- 做市模块（Market Making）—— 多空对冲网格策略基础表 5 张 + 菜单
-- 独立模块 mm-service，不污染 stg_* / trd_* 表
--
-- 表清单：
--   grid_strategy         网格策略主表（每个 symbol 一个实例）
--   grid_order            挂单快照 + 成交流水
--   grid_event            事件日志（fill / reshuffle / trend / emergency）
--   grid_pnl_snapshot     定期 P&L 快照（arb / directional / fee 归因）
--   grid_config_history   参数变更审计
--
-- 幂等：可重复执行
-- ============================================

-- 1) 策略主表
CREATE TABLE IF NOT EXISTS `grid_strategy` (
    `id`                            BIGINT      NOT NULL PRIMARY KEY,
    `name`                          VARCHAR(128) NOT NULL,
    `description`                   VARCHAR(500),
    `exchange`                      VARCHAR(32) NOT NULL DEFAULT 'binance-usdcm',
    `symbol`                        VARCHAR(64) NOT NULL,
    `account_id`                    BIGINT      NOT NULL,

    -- 生命周期
    `status`                        VARCHAR(24) NOT NULL DEFAULT 'STOPPED'
                                    COMMENT 'STOPPED/RUNNING/PAUSED/EMERGENCY_STOPPED',

    -- 网格几何（运行时会更新）
    `center_price`                  DECIMAL(20,8),
    `step_pct`                      DECIMAL(6,4)  COMMENT '当前生效步长（自适应会更新）',
    `window_size_per_side`          INT NOT NULL DEFAULT 10,
    `qty_per_grid`                  DECIMAL(20,8) NOT NULL,

    -- 基础参数
    `base_step_pct`                 DECIMAL(6,4) NOT NULL DEFAULT 0.5 COMMENT '步长基线%',
    `minimum_step_safety_multiplier` DECIMAL(4,2) DEFAULT 5.0 COMMENT 'minimum_step = spread × 此值',

    -- 风控上限（硬约束）
    `max_position_qty`              DECIMAL(20,8) NOT NULL COMMENT '单侧最大持仓',
    `max_notional_usd`              DECIMAL(20,8),
    `max_delta_qty`                 DECIMAL(20,8) COMMENT 'LONG-SHORT 净敞口上限',

    -- 模式开关
    `hedge_mode`                    TINYINT NOT NULL DEFAULT 1 COMMENT '1=双向对冲；0=单向 LONG grid',
    `long_side_enabled`             TINYINT NOT NULL DEFAULT 1,
    `short_side_enabled`            TINYINT NOT NULL DEFAULT 1,
    `adaptive_step_enabled`         TINYINT NOT NULL DEFAULT 1,
    `trend_protection_enabled`      TINYINT NOT NULL DEFAULT 1,

    -- 分周期趋势判据
    `trend_layer1_interval`         VARCHAR(8) DEFAULT '5m',
    `trend_layer2_interval`         VARCHAR(8) DEFAULT '15m',
    `trend_layer3_interval`         VARCHAR(8) DEFAULT '1h',
    `trend_layer4_interval`         VARCHAR(8) DEFAULT '4h',

    -- 分层 ADX 阈值（进/出，滞回）
    `trend_layer2_adx_enter`        DECIMAL(6,2) DEFAULT 25,
    `trend_layer2_adx_exit`         DECIMAL(6,2) DEFAULT 20,
    `trend_layer3_adx_enter`        DECIMAL(6,2) DEFAULT 30,
    `trend_layer3_adx_exit`         DECIMAL(6,2) DEFAULT 22,
    `trend_layer4_adx_enter`        DECIMAL(6,2) DEFAULT 35,
    -- Layer 4 无自动 exit，人工恢复

    -- 波动率（ATR）参数
    `vol_atr_period`                INT DEFAULT 14,
    `vol_atr_baseline_period`       INT DEFAULT 60,

    -- Window depletion 判据（事件驱动）
    `depletion_pct_layer2`          DECIMAL(4,2) DEFAULT 60.00,
    `depletion_pct_layer3`          DECIMAL(5,2) DEFAULT 100.00,
    `depletion_time_layer2_sec`     INT DEFAULT 1800,
    `depletion_time_layer3_sec`     INT DEFAULT 3600,

    -- 综合评分权重（多信号打分制）
    `score_weight_adx`              INT DEFAULT 3,
    `score_weight_depletion`        INT DEFAULT 3,
    `score_weight_asymmetry`        INT DEFAULT 2,
    `score_weight_consecutive`      INT DEFAULT 1,
    `score_weight_reverse_gap`      INT DEFAULT 1,
    `score_layer1_min`              INT DEFAULT 3,
    `score_layer2_min`              INT DEFAULT 5,
    `score_layer3_min`              INT DEFAULT 7,
    `score_layer4_min`              INT DEFAULT 9,

    -- 分侧减仓/停机阈值（占本金%）
    `long_reduce_pnl_pct`           DECIMAL(4,2) DEFAULT 1.0,
    `short_reduce_pnl_pct`          DECIMAL(4,2) DEFAULT 1.0,
    `long_stop_pnl_pct`             DECIMAL(4,2) DEFAULT 3.0,
    `short_stop_pnl_pct`            DECIMAL(4,2) DEFAULT 3.0,
    `account_emergency_pnl_pct`     DECIMAL(4,2) DEFAULT 5.0,
    `account_margin_usage_pct`      DECIMAL(4,2) DEFAULT 70.0,

    -- 梯队平仓（Ladder Close）参数
    `ladder_pct_of_position`        DECIMAL(4,2) DEFAULT 30
                                    COMMENT '梯队占累积仓位百分比',
    `ladder_step_count`             INT DEFAULT 5,
    `ladder_floor_at_avg_cost`      TINYINT DEFAULT 1 COMMENT '梯队最低价不低于平均成本',
    `ladder_min_reserve_pct`        DECIMAL(4,2) DEFAULT 0.05 COMMENT '现价上方缓冲%',

    -- Reshuffle / 冷却
    `reshuffle_price_move_grids`    INT DEFAULT 5,
    `reshuffle_cooldown_sec`        INT DEFAULT 300,
    `min_mode_change_interval_sec`  INT DEFAULT 300,
    `layer3_min_hold_sec`           INT DEFAULT 1800 COMMENT 'Layer3 触发后最短保持时长',
    `layer4_min_hold_sec`           INT DEFAULT 3600,
    `rapid_move_pause_pct`          DECIMAL(4,2) DEFAULT 2.0 COMMENT '5min 价格移动 > X% 暂停',

    -- 紧急平仓策略（Maker-only 前提）
    `emergency_taker_fallback_enabled` TINYINT DEFAULT 1
                                    COMMENT '极端行情下允许破例 taker 强平',
    `emergency_taker_loss_threshold`   DECIMAL(6,4) DEFAULT 0.03,
    `graceful_close_repricing_interval_sec` INT DEFAULT 5,

    -- 杠杆 / 保证金
    `leverage`                      INT NOT NULL DEFAULT 3,
    `margin_type`                   VARCHAR(16) DEFAULT 'ISOLATED',

    -- 运行时快照（读多写多；未来可迁 RocksDB）
    `last_reshuffle_at`             DATETIME,
    `last_adaptive_at`              DATETIME,
    `last_layer_change_at`          DATETIME,
    `current_active_layer`          VARCHAR(24) DEFAULT 'NORMAL'
                                    COMMENT 'NORMAL/LAYER1/LAYER2/LAYER3/LAYER4/EMERGENCY',
    `current_long_qty`              DECIMAL(20,8) DEFAULT 0,
    `current_short_qty`             DECIMAL(20,8) DEFAULT 0,
    `total_arbitrage_pnl`           DECIMAL(20,8) DEFAULT 0,
    `total_directional_pnl`         DECIMAL(20,8) DEFAULT 0,
    `total_fee_paid`                DECIMAL(20,8) DEFAULT 0,

    `create_by`                     BIGINT,
    `create_time`                   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_by`                     BIGINT,
    `update_time`                   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted`                       TINYINT NOT NULL DEFAULT 0,

    KEY `idx_status`  (`status`),
    KEY `idx_symbol`  (`exchange`, `symbol`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='做市模块-网格策略';

-- 2) 挂单快照
CREATE TABLE IF NOT EXISTS `grid_order` (
    `id`                    BIGINT NOT NULL PRIMARY KEY,
    `grid_strategy_id`      BIGINT NOT NULL,

    `exchange_order_id`     VARCHAR(64) UNIQUE COMMENT '币安 orderId',
    `client_order_id`       VARCHAR(64) UNIQUE COMMENT '我方生成，防重复',

    `side`                  VARCHAR(8)  NOT NULL COMMENT 'BUY/SELL',
    `position_side`         VARCHAR(8)  NOT NULL COMMENT 'LONG/SHORT (hedge)',
    `role`                  VARCHAR(24) NOT NULL COMMENT 'OPEN/CLOSE/LADDER_CLOSE',
    `grid_index`            INT COMMENT '网格线索引：0=中枢，±1=第一档...',
    `grid_price`            DECIMAL(20,8) NOT NULL,
    `qty`                   DECIMAL(20,8) NOT NULL,

    `status`                VARCHAR(24) NOT NULL DEFAULT 'PENDING'
                            COMMENT 'PENDING/LIVE/FILLED/PARTIAL/CANCELED/REJECTED',
    `filled_qty`            DECIMAL(20,8) DEFAULT 0,
    `avg_fill_price`        DECIMAL(20,8),
    `fee_paid`              DECIMAL(20,8) DEFAULT 0 COMMENT '应为 0（maker only）',

    `pair_order_id`         BIGINT COMMENT '配对平仓单 grid_order.id',

    `placed_at`             DATETIME,
    `filled_at`             DATETIME,
    `canceled_at`           DATETIME,
    `reject_reason`         VARCHAR(200),

    `create_by`             BIGINT,
    `create_time`           DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_by`             BIGINT,
    `update_time`           DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted`               TINYINT NOT NULL DEFAULT 0,

    KEY `idx_strategy_status` (`grid_strategy_id`, `status`),
    KEY `idx_exchange_order`  (`exchange_order_id`),
    KEY `idx_client_order`    (`client_order_id`),
    KEY `idx_strategy_price`  (`grid_strategy_id`, `grid_price`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='做市模块-挂单/成交流水';

-- 3) 事件流水
CREATE TABLE IF NOT EXISTS `grid_event` (
    `id`                BIGINT NOT NULL PRIMARY KEY,
    `grid_strategy_id`  BIGINT NOT NULL,
    `event_type`        VARCHAR(48) NOT NULL
                        COMMENT 'START/STOP/FILL/RESHUFFLE/STEP_ADJUST/TREND_ENTER/TREND_EXIT/LAYER_CHANGE/LADDER_PLACE/LADDER_FILL/DELTA_ALERT/MARGIN_ALERT/MAX_POS_REACHED/EMERGENCY_STOP/WS_DISCONNECT/WS_RECONNECT/GTX_REJECTED',
    `severity`          VARCHAR(16) DEFAULT 'INFO' COMMENT 'INFO/WARN/ERROR',
    `payload_json`      JSON,
    `occurred_at`       DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY `idx_strategy_time`   (`grid_strategy_id`, `occurred_at`),
    KEY `idx_type`            (`event_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='做市模块-事件流水';

-- 4) P&L 快照
CREATE TABLE IF NOT EXISTS `grid_pnl_snapshot` (
    `id`                        BIGINT NOT NULL PRIMARY KEY,
    `grid_strategy_id`          BIGINT NOT NULL,
    `snapshot_at`               DATETIME NOT NULL,

    `mark_price`                DECIMAL(20,8),
    `long_qty`                  DECIMAL(20,8),
    `short_qty`                 DECIMAL(20,8),
    `net_qty`                   DECIMAL(20,8) COMMENT 'long - short',
    `net_notional`              DECIMAL(20,8),
    `long_avg_cost`             DECIMAL(20,8),
    `short_avg_cost`            DECIMAL(20,8),

    `unrealized_pnl`            DECIMAL(20,8),
    `realized_arbitrage_pnl`    DECIMAL(20,8) COMMENT '累计',
    `realized_directional_pnl`  DECIMAL(20,8) COMMENT '累计',
    `funding_pnl`               DECIMAL(20,8) COMMENT '累计（对冲下应 ~0）',
    `fee_paid`                  DECIMAL(20,8),

    `round_trips_completed`     INT,
    `active_orders_count`       INT,
    `long_depletion_pct`        DECIMAL(4,2),
    `short_depletion_pct`       DECIMAL(4,2),
    `current_layer`             VARCHAR(24),
    `trend_score`               INT,

    KEY `idx_strategy_time` (`grid_strategy_id`, `snapshot_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='做市模块-P&L 快照';

-- 5) 参数变更审计
CREATE TABLE IF NOT EXISTS `grid_config_history` (
    `id`                BIGINT NOT NULL PRIMARY KEY,
    `grid_strategy_id`  BIGINT NOT NULL,
    `field_changed`     VARCHAR(64) NOT NULL,
    `old_value`         VARCHAR(500),
    `new_value`         VARCHAR(500),
    `change_source`     VARCHAR(24) DEFAULT 'USER'
                        COMMENT 'USER/ADAPTIVE/EMERGENCY',
    `changed_by`        BIGINT,
    `changed_at`        DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY `idx_strategy`  (`grid_strategy_id`),
    KEY `idx_changed_at`(`changed_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='做市模块-参数变更审计';

-- 6) sys_menu: 顶级目录"做市策略"（parent=0，独立顶级）
--    子级：网格策略列表（35）+ 策略详情/创建通过前端路由展开
INSERT INTO `sys_menu`
    (id, parent_id, name, i18n_key, path, component, icon, `type`, permission, sort, status, deleted, create_time, update_time)
VALUES
    (7,  0, '做市策略', 'text.mm.title', NULL, NULL, 'FunctionOutlined', 0, NULL, 6, 1, 0, NOW(), NOW()),
    (35, 7, '网格策略', 'text.mm.gridListTitle', '/mm/grid',
     'GridStrategyList', 'AppstoreOutlined', 1, 'mm:grid', 1, 1, 0, NOW(), NOW())
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

-- 7) 绑定到 administrator 角色
INSERT IGNORE INTO `sys_role_menu`
    (id, role_id, menu_id, create_by, create_time, update_by, update_time, deleted)
SELECT
    9000000000 + ROW_NUMBER() OVER (ORDER BY r.id) + 350 AS id,
    r.id AS role_id,
    m.mid AS menu_id,
    1, NOW(), 1, NOW(), 0
FROM `sys_role` r
CROSS JOIN (SELECT 7 AS mid UNION ALL SELECT 35) m
WHERE r.code = 'administrator' AND r.deleted = 0;

-- 8) 兼容软删除恢复
UPDATE `sys_menu` SET deleted = 0, update_time = NOW() WHERE id IN (7, 35) AND deleted = 1;
UPDATE `sys_role_menu` SET deleted = 0, update_time = NOW() WHERE menu_id IN (7, 35) AND deleted = 1;

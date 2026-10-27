-- ============================================================================
-- V38: 资金费率套利模块 - 5 张业务表 + 菜单
--
-- 数据分工：
--   MySQL      → 策略配置 / 持仓 / 结算日志 / 事件流水 / 配置审计（关系性、事务性）
--   RocksDB    → 时序信号（funding/OI/LSR/basis/vol），key 前缀 farb:* 走 quote-service
--                的 RocksDBManager，不建 SQL 表
--
-- 设计文档：service/DESIGN_FUNDING_ARB.md
-- ============================================================================

-- ─── 1. 策略主表 ────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS farb_strategy (
    id                              BIGINT PRIMARY KEY,
    name                            VARCHAR(64) NOT NULL COMMENT '策略名称',
    description                     VARCHAR(255) DEFAULT NULL,

    -- 账户绑定（用现有 ExchangeAccount 体系）
    spot_account_id                 BIGINT NOT NULL COMMENT '现货账户 ID（marketType=SPOT）',
    perp_account_id                 BIGINT NOT NULL COMMENT '期货账户 ID（marketType=USDM）',

    -- 状态机
    status                          VARCHAR(20) NOT NULL DEFAULT 'STOPPED'
                                    COMMENT 'STOPPED / RUNNING / PAUSED / HIBERNATE',
    execution_mode                  VARCHAR(10) NOT NULL DEFAULT 'PAPER'
                                    COMMENT 'PAPER / LIVE',

    -- ─── Symbol 过滤 ────────────────────────────
    scan_all_market                 TINYINT NOT NULL DEFAULT 1 COMMENT '是否扫全市场',
    symbol_whitelist                TEXT COMMENT 'JSON: ["BTCUSDT",...]，覆盖全市场',
    symbol_blacklist                TEXT COMMENT 'JSON: 强制排除',
    min_24h_volume_usd              DECIMAL(20,2) NOT NULL DEFAULT 10000000
                                    COMMENT '24h 名义成交额下限 USD',

    -- ─── Regime 参数（多信号融合，v2.1）────────
    lookback_settlements            INT NOT NULL DEFAULT 6 COMMENT '判定 regime 用过去多少次结算',
    min_avg_funding_bps             DECIMAL(6,2) NOT NULL DEFAULT 4.00
                                    COMMENT '过去 N 次平均 funding ≥ 此值（8h 归一化, bps）',
    min_hit_ratio                   DECIMAL(4,2) NOT NULL DEFAULT 0.67
                                    COMMENT 'N 次中至少达标比例',
    min_latest_funding_bps          DECIMAL(6,2) NOT NULL DEFAULT 2.00
                                    COMMENT '最近一次 funding 不能反转',
    max_entry_basis_bps             DECIMAL(6,2) NOT NULL DEFAULT 20.00
                                    COMMENT '入场时 basis 绝对值上限',
    lsr_max_extreme                 DECIMAL(6,2) NOT NULL DEFAULT 3.00
                                    COMMENT 'LSR 极端拥挤上限',
    oi_crowding_threshold_pct       DECIMAL(6,2) NOT NULL DEFAULT 30.00
                                    COMMENT 'OI 24h 涨幅上限（拥挤过滤）',
    vol_spike_multiplier            DECIMAL(6,2) NOT NULL DEFAULT 2.00
                                    COMMENT '24h vol / 72h vol 上限',
    require_taker_confirm           TINYINT NOT NULL DEFAULT 0
                                    COMMENT '是否要求 taker BSR 与 funding 同向',

    -- ─── 出场触发参数 ───────────────────────────
    exit_regime_break_count         INT NOT NULL DEFAULT 2
                                    COMMENT '连续 N 次结算不达标即平',
    exit_basis_delta_bps            DECIMAL(6,2) NOT NULL DEFAULT 60.00
                                    COMMENT 'basis 反向变化触发平仓 bps',
    exit_take_profit_pct            DECIMAL(6,2) NOT NULL DEFAULT 0.80
                                    COMMENT '累计净收益达此值主动止盈 %',
    exit_max_hold_hours             INT NOT NULL DEFAULT 168
                                    COMMENT '最长持仓小时（默认 7 天）',
    basis_emergency_bps             DECIMAL(6,2) NOT NULL DEFAULT 120.00
                                    COMMENT 'basis 极端反向立即市价平',

    -- ─── 仓位参数 ───────────────────────────────
    notional_per_pair               DECIMAL(20,2) NOT NULL DEFAULT 500.00
                                    COMMENT '单笔名义 USD',
    max_open_pairs                  INT NOT NULL DEFAULT 8 COMMENT '全策略最大并发对数',
    max_pairs_per_symbol            INT NOT NULL DEFAULT 1 COMMENT '单币种最大对数',
    perp_leverage                   INT NOT NULL DEFAULT 2 COMMENT '期货杠杆',
    perp_margin_type                VARCHAR(10) NOT NULL DEFAULT 'ISOLATED'
                                    COMMENT 'ISOLATED / CROSS',

    -- ─── 手续费参数 ─────────────────────────────
    use_realtime_fee                TINYINT NOT NULL DEFAULT 1
                                    COMMENT '入场前实时拉 Binance tradeFee',
    use_bnb_discount                TINYINT NOT NULL DEFAULT 1,
    spot_fee_rate                   DECIMAL(6,5) NOT NULL DEFAULT 0.00075
                                    COMMENT '现货 taker fee（静态兜底）',
    perp_fee_rate                   DECIMAL(6,5) NOT NULL DEFAULT 0.00036
                                    COMMENT '期货 taker fee（静态兜底）',
    min_expected_yield_pct          DECIMAL(6,2) NOT NULL DEFAULT 8.00
                                    COMMENT '预期净年化 < 此值不入场',

    -- ─── 风控参数 ───────────────────────────────
    max_drawdown_pct                DECIMAL(6,2) NOT NULL DEFAULT 15.00
                                    COMMENT '全策略回撤触发暂停',
    funding_reversal_stop_days      INT NOT NULL DEFAULT 3
                                    COMMENT '连续 N 天全市场 funding 反转触发 HIBERNATE',

    -- ─── 运行时快照 ─────────────────────────────
    current_open_pairs              INT DEFAULT 0 COMMENT '当前 OPEN pair 数',
    total_funding_pnl               DECIMAL(20,4) DEFAULT 0,
    total_basis_pnl                 DECIMAL(20,4) DEFAULT 0,
    total_fee_paid                  DECIMAL(20,4) DEFAULT 0,
    total_net_pnl                   DECIMAL(20,4) DEFAULT 0,
    total_pairs_opened              INT DEFAULT 0,
    total_pairs_closed              INT DEFAULT 0,

    -- ─── 审计 ───────────────────────────────────
    create_by                       BIGINT DEFAULT NULL,
    create_time                     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_by                       BIGINT DEFAULT NULL,
    update_time                     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted                         TINYINT NOT NULL DEFAULT 0,

    INDEX idx_status (status, execution_mode),
    INDEX idx_deleted (deleted)
) ENGINE=InnoDB COMMENT='资金费套利策略主表';


-- ─── 2. 持仓表 ─────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS farb_position (
    id                              BIGINT PRIMARY KEY,
    strategy_id                     BIGINT NOT NULL,
    symbol                          VARCHAR(20) NOT NULL,

    -- 生命周期状态
    status                          VARCHAR(20) NOT NULL
                                    COMMENT 'PENDING_ENTRY / OPEN / CLOSING / CLOSED / EMERGENCY_CLOSED / ZOMBIE',

    -- ─── 入场 ───────────────────────────────────
    entry_time                      DATETIME NOT NULL,
    entry_spot_price                DECIMAL(20,8) DEFAULT NULL,
    entry_perp_price                DECIMAL(20,8) DEFAULT NULL,
    entry_basis_bps                 DECIMAL(8,2) DEFAULT NULL,
    entry_funding_rate              DECIMAL(10,8) DEFAULT NULL COMMENT '入场时该 symbol 的 fundingRate',
    entry_regime_snapshot           TEXT COMMENT 'JSON: {avg, hit_ratio, latest, oi, lsr, ...}',
    projected_net_yield_pct         DECIMAL(6,2) DEFAULT NULL,
    projected_regime_hours          INT DEFAULT NULL,

    spot_qty                        DECIMAL(20,8) NOT NULL,
    perp_qty                        DECIMAL(20,8) NOT NULL,
    notional_at_entry               DECIMAL(20,4) DEFAULT NULL,

    entry_spot_order_id             VARCHAR(64) DEFAULT NULL,
    entry_spot_client_id            VARCHAR(64) DEFAULT NULL,
    entry_perp_order_id             VARCHAR(64) DEFAULT NULL,
    entry_perp_client_id            VARCHAR(64) DEFAULT NULL,
    entry_spot_fee                  DECIMAL(20,8) DEFAULT 0,
    entry_perp_fee                  DECIMAL(20,8) DEFAULT 0,

    -- ─── 结算追踪 ───────────────────────────────
    next_expected_settlement_time   DATETIME DEFAULT NULL,
    last_settlement_time            DATETIME DEFAULT NULL,
    settlement_count                INT DEFAULT 0,
    actual_funding_pnl              DECIMAL(20,4) DEFAULT 0 COMMENT '累计已收 funding',

    -- ─── 出场 ───────────────────────────────────
    close_time                      DATETIME DEFAULT NULL,
    close_trigger                   VARCHAR(30) DEFAULT NULL
                                    COMMENT 'REGIME_BREAK / BASIS_BREACH / TAKE_PROFIT / MAX_HOLD / EMERGENCY / MANUAL',
    exit_spot_price                 DECIMAL(20,8) DEFAULT NULL,
    exit_perp_price                 DECIMAL(20,8) DEFAULT NULL,
    exit_basis_bps                  DECIMAL(8,2) DEFAULT NULL,
    exit_spot_order_id              VARCHAR(64) DEFAULT NULL,
    exit_perp_order_id              VARCHAR(64) DEFAULT NULL,
    exit_spot_fee                   DECIMAL(20,8) DEFAULT 0,
    exit_perp_fee                   DECIMAL(20,8) DEFAULT 0,

    -- ─── P&L 归因 ───────────────────────────────
    funding_pnl                     DECIMAL(20,4) DEFAULT 0,
    basis_pnl                       DECIMAL(20,4) DEFAULT 0,
    fee_paid                        DECIMAL(20,4) DEFAULT 0,
    net_pnl                         DECIMAL(20,4) DEFAULT 0,

    create_time                     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time                     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    INDEX idx_strategy_status (strategy_id, status),
    INDEX idx_symbol_time (symbol, entry_time),
    INDEX idx_next_settlement (next_expected_settlement_time),
    INDEX idx_close_time (close_time)
) ENGINE=InnoDB COMMENT='资金费套利持仓 (一对 = 现货多头 + 期货空头)';


-- ─── 3. 结算日志 ───────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS farb_settlement_log (
    id                              BIGINT PRIMARY KEY AUTO_INCREMENT,
    strategy_id                     BIGINT NOT NULL,
    position_id                     BIGINT NOT NULL,
    symbol                          VARCHAR(20) NOT NULL,

    settlement_time                 DATETIME NOT NULL,
    funding_rate                    DECIMAL(10,8) NOT NULL COMMENT '此次结算的 fundingRate',
    funding_interval_hours          INT NOT NULL,
    mark_price_at_settle            DECIMAL(20,8) DEFAULT NULL,
    perp_qty                        DECIMAL(20,8) NOT NULL,
    funding_amount                  DECIMAL(20,8) NOT NULL COMMENT '本次结算 P&L（正=收，负=付）',

    source                          VARCHAR(20) NOT NULL DEFAULT 'USER_DATA_STREAM'
                                    COMMENT 'USER_DATA_STREAM / REST_FALLBACK / PAPER_MOCK / LOCAL_ESTIMATE',
    binance_income_id               VARCHAR(64) DEFAULT NULL,

    create_time                     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,

    INDEX idx_position (position_id, settlement_time),
    INDEX idx_strategy (strategy_id, settlement_time),
    INDEX idx_symbol_time (symbol, settlement_time)
) ENGINE=InnoDB COMMENT='资金费套利结算记账';


-- ─── 4. 事件日志 ───────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS farb_event_log (
    id                              BIGINT PRIMARY KEY AUTO_INCREMENT,
    strategy_id                     BIGINT NOT NULL,
    position_id                     BIGINT DEFAULT NULL,
    symbol                          VARCHAR(20) DEFAULT NULL,
    event_type                      VARCHAR(40) NOT NULL
                                    COMMENT '完整事件类型见 FarbEventLogger（SCAN_TICK / REGIME_* / PAIR_* / SETTLEMENT_* / EXIT_* / BASIS_* / RECOVERY_* 等）',
    severity                        VARCHAR(10) NOT NULL DEFAULT 'INFO'
                                    COMMENT 'DEBUG / INFO / WARN / ERROR',
    payload                         TEXT COMMENT 'JSON: 事件详细字段',
    occurred_at                     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,

    INDEX idx_strategy_time (strategy_id, occurred_at),
    INDEX idx_position (position_id),
    INDEX idx_type (event_type, occurred_at)
) ENGINE=InnoDB COMMENT='资金费套利事件流水';


-- ─── 5. 配置变更审计 ────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS farb_config_history (
    id                              BIGINT PRIMARY KEY AUTO_INCREMENT,
    strategy_id                     BIGINT NOT NULL,
    field_changed                   VARCHAR(64) NOT NULL,
    old_value                       TEXT DEFAULT NULL,
    new_value                       TEXT DEFAULT NULL,
    change_source                   VARCHAR(20) NOT NULL DEFAULT 'USER'
                                    COMMENT 'USER / SYSTEM / RECOVERY',
    changed_by                      BIGINT DEFAULT NULL,
    changed_at                      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,

    INDEX idx_strategy_time (strategy_id, changed_at)
) ENGINE=InnoDB COMMENT='资金费套利参数变更审计';


-- ─── 6. sys_menu：顶级目录"资金费套利" + 子级"策略列表" ───────────────────
-- 字段对齐 system_tables.sql：type / permission / sort / status
INSERT IGNORE INTO sys_menu
    (id, parent_id, name, i18n_key, path, component, icon, type, permission, sort, status, deleted, create_time, update_time)
VALUES
    (8,   0, '资金费套利', 'text.farb.title',      NULL,        NULL,               'DollarOutlined',      0, NULL,          7, 1, 0, NOW(), NOW()),
    (36,  8, '策略列表',   'text.farb.listTitle',  '/farb',     'FarbStrategyList', 'AppstoreOutlined',    1, 'farb:strategy', 1, 1, 0, NOW(), NOW());
